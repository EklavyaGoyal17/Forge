package dev.forge.server.run;

import dev.forge.lab.runner.RunnerMain;
import dev.forge.lab.scenario.RunSpec;
import dev.forge.server.config.Json;
import dev.forge.server.config.RunnerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Starts the runner in a separate JVM and supervises it.
 *
 * <p>Isolation controls applied here (see docs/SECURITY.md §2):
 * <ul>
 *   <li>Arguments are passed as an array plus a JVM {@code @argfile}; no shell is ever involved.</li>
 *   <li>The child's environment is cleared; only what the JVM needs on this OS is re-added.</li>
 *   <li>Heap ceiling, OOM-exit, optional processor-count cap, private temp/working directory.</li>
 *   <li>Wall-clock timeout, cooperative cancellation, stdout/stderr byte caps; on any of these the
 *       whole process tree is destroyed.</li>
 * </ul>
 */
@Component
public class RunnerLauncher {

    private static final Logger log = LoggerFactory.getLogger(RunnerLauncher.class);
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");

    private final RunnerProperties props;
    private final Json json;
    private final Path workRoot;
    private volatile String mainClass = RunnerMain.class.getName();

    public RunnerLauncher(RunnerProperties props, Json json) throws IOException {
        this.props = props;
        this.json = json;
        this.workRoot = props.workRoot() == null || props.workRoot().isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "forge-runs")
                : Path.of(props.workRoot());
        Files.createDirectories(workRoot);
    }

    public enum Termination { EXITED, TIMED_OUT, CANCELLED, OUTPUT_LIMIT }

    public record Outcome(Termination termination, int exitCode, String stderr, boolean stderrTruncated,
                          List<String> commandLine) {
    }

    /** Polled while the child runs; returning true kills it. */
    @FunctionalInterface
    public interface CancelSignal {
        boolean cancelled();
    }

    public Outcome launch(RunSpec spec, int heapMb, int timeoutSeconds, Consumer<JsonNode> onMessage,
                          CancelSignal cancel) throws IOException, InterruptedException {
        Path workDir = Files.createDirectories(workRoot.resolve("run-" + spec.runId()));
        try {
            return supervise(spec, heapMb, timeoutSeconds, workDir, onMessage, cancel);
        } finally {
            if (!props.keepWorkDirs()) {
                deleteRecursively(workDir);
            }
        }
    }

    List<String> jvmArguments(int heapMb, int timeoutSeconds, Path workDir) {
        List<String> args = new ArrayList<>();
        args.add("-Xms" + Math.min(heapMb, 64) + "m");
        args.add("-Xmx" + heapMb + "m");
        args.add("-XX:+ExitOnOutOfMemoryError");
        args.add("-XX:-UsePerfData");           // no hsperfdata files outside the work dir
        if (props.activeProcessorCount() > 0) {
            args.add("-XX:ActiveProcessorCount=" + props.activeProcessorCount());
        }
        args.add("-Djava.io.tmpdir=" + workDir);
        args.add("-Dforge.workDir=" + workDir);
        args.add("-Dforge.timeoutMillis=" + timeoutSeconds * 1000L);
        args.add("-Dfile.encoding=UTF-8");
        args.add("-Dstdout.encoding=UTF-8");
        args.add("-cp");
        args.add(classpath());
        args.add(mainClass);
        return args;
    }

    /** Tests substitute a deliberately misbehaving main class to exercise the kill paths. */
    void useMainClassForTesting(String className) {
        this.mainClass = className;
    }

    private Outcome supervise(RunSpec spec, int heapMb, int timeoutSeconds, Path workDir,
                              Consumer<JsonNode> onMessage, CancelSignal cancel) throws IOException, InterruptedException {
        List<String> jvmArgs = jvmArguments(heapMb, timeoutSeconds, workDir);
        // An @argfile avoids the ~32k command-line limit on Windows for long development classpaths.
        Path argFile = workDir.resolve("jvm.args");
        Files.writeString(argFile, String.join("\n", jvmArgs.stream().map(RunnerLauncher::quoteArg).toList()),
                StandardCharsets.UTF_8);

        ProcessBuilder pb = new ProcessBuilder(javaExecutable(), "@" + argFile);
        pb.directory(workDir.toFile());
        Map<String, String> env = pb.environment();
        env.clear();
        env.putAll(minimalEnvironment(workDir));

        Process process = pb.start();
        log.info("run {} started runner pid {}", spec.runId(), process.pid());
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(json.write(spec).getBytes(StandardCharsets.UTF_8));
        }

        AtomicBoolean outputLimit = new AtomicBoolean();
        StderrCollector stderr = new StderrCollector(process.getErrorStream(), props.stderrCapBytes());
        Thread stdoutThread = Thread.ofPlatform().name("runner-stdout-" + spec.runId()).daemon().start(
                () -> readProtocol(process, onMessage, outputLimit));
        Thread stderrThread = Thread.ofPlatform().name("runner-stderr-" + spec.runId()).daemon().start(stderr);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        Termination termination = Termination.EXITED;
        while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
            if (outputLimit.get()) {
                termination = Termination.OUTPUT_LIMIT;
                break;
            }
            if (cancel.cancelled()) {
                termination = Termination.CANCELLED;
                break;
            }
            if (System.nanoTime() - deadline > 0) {
                termination = Termination.TIMED_OUT;
                break;
            }
        }
        if (termination != Termination.EXITED) {
            log.warn("run {}: {} — destroying runner process tree", spec.runId(), termination);
            destroyTree(process);
        }
        stdoutThread.join(5_000);
        stderrThread.join(5_000);
        int exit = process.isAlive() ? -1 : process.exitValue();
        if (outputLimit.get()) {
            termination = Termination.OUTPUT_LIMIT;
        }
        return new Outcome(termination, exit, stderr.text(), stderr.truncated(), jvmArgs);
    }

    private void readProtocol(Process process, Consumer<JsonNode> onMessage, AtomicBoolean outputLimit) {
        AtomicLong bytes = new AtomicLong();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (bytes.addAndGet(line.length() + 1L) > props.stdoutCapBytes()) {
                    outputLimit.set(true);
                    return;
                }
                if (line.isBlank()) {
                    continue;
                }
                JsonNode message;
                try {
                    message = json.read(line);
                } catch (RuntimeException e) {
                    // System.out is redirected in the runner, so this indicates a protocol fault
                    log.warn("runner wrote non-protocol output: {}", line.length() > 200 ? line.substring(0, 200) : line);
                    continue;
                }
                try {
                    onMessage.accept(message);
                } catch (RuntimeException e) {
                    log.error("failed to handle runner message", e);
                }
            }
        } catch (IOException e) {
            log.debug("runner stdout closed: {}", e.getMessage());
        }
    }

    static void destroyTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    private Map<String, String> minimalEnvironment(Path workDir) {
        Path javaBin = Path.of(javaExecutable()).getParent();
        Map<String, String> env = new java.util.HashMap<>();
        if (javaBin != null) {
            env.put(WINDOWS ? "Path" : "PATH", javaBin.toString());
        }
        if (WINDOWS) {
            // Required by the JVM and Winsock (JMH talks to its forks over loopback).
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot != null) {
                env.put("SystemRoot", systemRoot);
            }
            env.put("TEMP", workDir.toString());
            env.put("TMP", workDir.toString());
        } else {
            env.put("TMPDIR", workDir.toString());
            env.put("LANG", "C.UTF-8");
        }
        return env;
    }

    String javaExecutable() {
        if (props.javaExecutable() != null && !props.javaExecutable().isBlank()) {
            return props.javaExecutable();
        }
        return Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java").toString();
    }

    String classpath() {
        if (props.classpath() != null && !props.classpath().isBlank()) {
            return props.classpath();
        }
        return System.getProperty("java.class.path");
    }

    /** Quotes one argument for a JVM @argfile: backslash and double quote are escape characters. */
    static String quoteArg(String arg) {
        return "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.debug("could not delete {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("could not clean {}: {}", dir, e.getMessage());
        }
    }

    /** Keeps the first {@code cap} bytes of stderr and discards (but drains) the rest. */
    static final class StderrCollector implements Runnable {
        private final InputStream in;
        private final int cap;
        private final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        private volatile boolean truncated;

        StderrCollector(InputStream in, int cap) {
            this.in = in;
            this.cap = cap;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(chunk)) != -1) {
                    synchronized (buf) {
                        int room = cap - buf.size();
                        if (room > 0) {
                            buf.write(chunk, 0, Math.min(room, n));
                        }
                        if (n > room) {
                            truncated = true;
                        }
                    }
                }
            } catch (IOException ignored) {
                // process ended
            }
        }

        String text() {
            synchronized (buf) {
                return buf.toString(StandardCharsets.UTF_8);
            }
        }

        boolean truncated() {
            return truncated;
        }
    }
}
