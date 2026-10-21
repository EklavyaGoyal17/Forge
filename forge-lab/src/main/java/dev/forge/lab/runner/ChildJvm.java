package dev.forge.lab.runner;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Starts the runner in a separate JVM and supervises it. Used by the server when it launches runs
 * itself, and by {@link RunnerAgent} in the dedicated runner container.
 *
 * <p>Isolation controls applied here (docs/SECURITY.md §2): arguments as an array plus a JVM
 * {@code @argfile} (no shell); a cleared environment with only what the JVM needs on this OS; heap
 * ceiling, OOM-exit, optional processor-count cap, private temp and working directory; per-variant
 * JVM flags only from {@link #ALLOWED_VARIANT_FLAGS}; wall-clock timeout, cancellation and
 * stdout/stderr byte caps, on any of which the whole process tree is destroyed.
 */
public final class ChildJvm {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");

    /**
     * The only flags a scenario variant may add to the runner command line: execution-mode switches
     * that change how code is compiled, not what the process may access.
     */
    public static final Set<String> ALLOWED_VARIANT_FLAGS = Set.of("-Xint", "-XX:-TieredCompilation", "-XX:TieredStopAtLevel=1");

    private ChildJvm() {
    }

    public enum Termination { EXITED, TIMED_OUT, CANCELLED, OUTPUT_LIMIT }

    public record Outcome(Termination termination, int exitCode, String stderr, boolean stderrTruncated, List<String> commandLine) {
    }

    /** Polled while the child runs; returning true kills it. */
    @FunctionalInterface
    public interface CancelSignal {
        boolean cancelled();
    }

    /** Where and how a runner JVM is started. */
    public record Launch(String javaExecutable, String classpath, String mainClass, int activeProcessorCount,
                         long stdoutCapBytes, int stderrCapBytes) {
    }

    public static List<String> jvmArguments(Launch l, int heapMb, int timeoutSeconds, Path workDir, List<String> variantFlags) {
        List<String> args = new ArrayList<>();
        args.add("-Xms" + Math.min(heapMb, 64) + "m");
        args.add("-Xmx" + heapMb + "m");
        args.add("-XX:+ExitOnOutOfMemoryError");
        args.add("-XX:-UsePerfData");           // no hsperfdata files outside the work dir
        if (l.activeProcessorCount() > 0) {
            args.add("-XX:ActiveProcessorCount=" + l.activeProcessorCount());
        }
        args.addAll(vetVariantFlags(variantFlags));
        args.add("-Djava.io.tmpdir=" + workDir);
        args.add("-Dforge.workDir=" + workDir);
        args.add("-Dforge.timeoutMillis=" + timeoutSeconds * 1000L);
        args.add("-Dfile.encoding=UTF-8");
        args.add("-Dstdout.encoding=UTF-8");
        args.add("-cp");
        args.add(l.classpath());
        args.add(l.mainClass());
        return args;
    }

    public static List<String> vetVariantFlags(List<String> flags) {
        for (String f : flags) {
            if (!ALLOWED_VARIANT_FLAGS.contains(f)) {
                throw new IllegalStateException("variant JVM flag not on the allow-list: " + f);
            }
        }
        return flags;
    }

    /**
     * Runs one runner JVM to completion in {@code workDir}: writes {@code stdin}, passes every
     * non-blank stdout line to {@code onLine} (until the stdout cap), collects stderr up to its cap,
     * and enforces the timeout and cancellation.
     */
    public static Outcome run(Launch l, String runId, int heapMb, int timeoutSeconds, List<String> variantFlags, Path workDir,
                              byte[] stdin, Consumer<String> onLine, CancelSignal cancel) throws IOException, InterruptedException {
        List<String> jvmArgs = jvmArguments(l, heapMb, timeoutSeconds, workDir, variantFlags);
        // An @argfile avoids the ~32k command-line limit on Windows for long development classpaths.
        Path argFile = workDir.resolve("jvm.args");
        Files.writeString(argFile, String.join("\n", jvmArgs.stream().map(ChildJvm::quoteArg).toList()), StandardCharsets.UTF_8);

        ProcessBuilder pb = new ProcessBuilder(l.javaExecutable(), "@" + argFile);
        pb.directory(workDir.toFile());
        Map<String, String> env = pb.environment();
        env.clear();
        env.putAll(minimalEnvironment(l.javaExecutable(), workDir));

        Process process = pb.start();
        try (OutputStream in = process.getOutputStream()) {
            in.write(stdin);
        }

        AtomicBoolean outputLimit = new AtomicBoolean();
        StderrCollector stderr = new StderrCollector(process.getErrorStream(), l.stderrCapBytes());
        Thread stdoutThread = Thread.ofPlatform().name("runner-stdout-" + runId).daemon().start(
                () -> readLines(process, l.stdoutCapBytes(), onLine, outputLimit));
        Thread stderrThread = Thread.ofPlatform().name("runner-stderr-" + runId).daemon().start(stderr);

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

    private static void readLines(Process process, long cap, Consumer<String> onLine, AtomicBoolean outputLimit) {
        AtomicLong bytes = new AtomicLong();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (bytes.addAndGet(line.length() + 1L) > cap) {
                    outputLimit.set(true);
                    return;
                }
                if (!line.isBlank()) {
                    onLine.accept(line);
                }
            }
        } catch (IOException e) {
            // stdout closed
        }
    }

    public static void destroyTree(Process process) {
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

    static Map<String, String> minimalEnvironment(String javaExecutable, Path workDir) {
        Path javaBin = Path.of(javaExecutable).getParent();
        Map<String, String> env = new HashMap<>();
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

    public static String defaultJavaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java").toString();
    }

    /** Quotes one argument for a JVM @argfile: backslash and double quote are escape characters. */
    public static String quoteArg(String arg) {
        return "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** Keeps the first {@code cap} bytes of stderr and discards (but drains) the rest. */
    static final class StderrCollector implements Runnable {
        private final InputStream in;
        private final int cap;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
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
