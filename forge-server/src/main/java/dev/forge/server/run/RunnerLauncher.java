package dev.forge.server.run;

import dev.forge.lab.runner.ChildJvm;
import dev.forge.lab.runner.RunnerMain;
import dev.forge.lab.scenario.RunSpec;
import dev.forge.lab.scenario.ScenarioCatalog;
import dev.forge.lab.scenario.VariantSpec;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Launches a run's runner JVM and supervises it — in this process ({@link ChildJvm}), or, when
 * {@code forge.runner.agent-url} is set, through the runner agent in the dedicated runner container
 * ({@link dev.forge.lab.runner.RunnerAgent}). Either way the same isolation controls apply; the
 * agent applies them on its side and the server only relays the protocol.
 */
@Component
public class RunnerLauncher {

    private static final Logger log = LoggerFactory.getLogger(RunnerLauncher.class);

    /** See {@link ChildJvm#ALLOWED_VARIANT_FLAGS}. */
    static final Set<String> ALLOWED_VARIANT_FLAGS = ChildJvm.ALLOWED_VARIANT_FLAGS;

    private final RunnerProperties props;
    private final Json json;
    private final Path workRoot;
    private final HttpClient agent;
    private volatile String mainClass = RunnerMain.class.getName();

    public RunnerLauncher(RunnerProperties props, Json json) throws IOException {
        this.props = props;
        this.json = json;
        this.workRoot = props.workRoot() == null || props.workRoot().isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "forge-runs")
                : Path.of(props.workRoot());
        Files.createDirectories(workRoot);
        // HTTP/1.1: the agent is the JDK HttpServer, which mishandles the h2c upgrade on a POST with a body
        this.agent = remote() ? HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).proxy(HttpClient.Builder.NO_PROXY)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build() : null;
    }

    public enum Termination { EXITED, TIMED_OUT, CANCELLED, OUTPUT_LIMIT }

    /** @param host facts about the machine the run executed on, when it was not this process's host (else null) */
    public record Outcome(Termination termination, int exitCode, String stderr, boolean stderrTruncated,
                          List<String> commandLine, Map<String, Object> host) {
    }

    /** Polled while the child runs; returning true kills it. */
    @FunctionalInterface
    public interface CancelSignal {
        boolean cancelled();
    }

    public boolean remote() {
        return props.agentUrl() != null && !props.agentUrl().isBlank();
    }

    public Outcome launch(RunSpec spec, int heapMb, int timeoutSeconds, Consumer<JsonNode> onMessage,
                          CancelSignal cancel) throws IOException, InterruptedException {
        if (remote()) {
            return launchRemote(spec, heapMb, timeoutSeconds, onMessage, cancel);
        }
        Path workDir = Files.createDirectories(workRoot.resolve("run-" + spec.runId()));
        try {
            ChildJvm.Outcome o = ChildJvm.run(launchConfig(), spec.runId(), heapMb, timeoutSeconds, variantFlags(spec), workDir,
                    json.write(spec).getBytes(StandardCharsets.UTF_8), line -> relay(line, onMessage), cancel::cancelled);
            if (o.termination() != ChildJvm.Termination.EXITED) {
                log.warn("run {}: {} — runner process tree destroyed", spec.runId(), o.termination());
            }
            return new Outcome(Termination.valueOf(o.termination().name()), o.exitCode(), o.stderr(), o.stderrTruncated(),
                    o.commandLine(), null);
        } finally {
            if (!props.keepWorkDirs()) {
                ChildJvm.deleteRecursively(workDir);
            }
        }
    }

    // ------------------------------------------------------------------ remote (runner container)

    private Outcome launchRemote(RunSpec spec, int heapMb, int timeoutSeconds, Consumer<JsonNode> onMessage, CancelSignal cancel)
            throws IOException, InterruptedException {
        String base = props.agentUrl().replaceAll("/+$", "");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", spec.runId());
        body.put("heapMb", heapMb);
        body.put("timeoutSeconds", timeoutSeconds);
        body.put("spec", spec);
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/runs"))
                .timeout(Duration.ofSeconds(timeoutSeconds + 120L))
                .header("Content-Type", "application/json")
                .header("X-Forge-Runner-Token", props.agentToken())
                .POST(HttpRequest.BodyPublishers.ofString(json.write(body)))
                .build();
        HttpResponse<InputStream> resp = agent.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            String text;
            try (InputStream in = resp.body()) {
                text = new String(in.readNBytes(2_000), StandardCharsets.UTF_8);
            }
            throw new IOException("runner agent answered HTTP " + resp.statusCode() + ": " + text);
        }
        AtomicBoolean done = new AtomicBoolean();
        Thread watcher = Thread.ofPlatform().name("runner-cancel-" + spec.runId()).daemon().start(() -> {
            while (!done.get()) {
                if (cancel.cancelled()) {
                    try {
                        agent.send(HttpRequest.newBuilder(URI.create(base + "/runs/" + spec.runId() + "/cancel"))
                                .timeout(Duration.ofSeconds(10)).header("X-Forge-Runner-Token", props.agentToken())
                                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
                    } catch (IOException e) {
                        log.warn("could not cancel run {} on the runner agent: {}", spec.runId(), e.getMessage());
                    } catch (InterruptedException e) {
                        return;
                    }
                    return;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        JsonNode outcome = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode msg;
                try {
                    msg = json.read(line);
                } catch (RuntimeException e) {
                    log.warn("runner wrote non-protocol output: {}", line.length() > 200 ? line.substring(0, 200) : line);
                    continue;
                }
                if ("outcome".equals(msg.path("agent").asString(null))) {
                    outcome = msg;
                } else {
                    deliver(msg, onMessage);
                }
            }
        } finally {
            done.set(true);
            watcher.interrupt();
        }
        if (outcome == null) {
            throw new IOException("the runner agent closed the connection without an outcome");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> host = json.convert(outcome.get("host"), Map.class);
        List<String> commandLine = new java.util.ArrayList<>();
        outcome.path("commandLine").forEach(a -> commandLine.add(a.asString()));
        return new Outcome(Termination.valueOf(outcome.path("termination").asString("EXITED")), outcome.path("exitCode").asInt(-1),
                outcome.path("stderr").asString(""), outcome.path("stderrTruncated").asBoolean(), commandLine, host);
    }

    // ------------------------------------------------------------------ shared

    private void relay(String line, Consumer<JsonNode> onMessage) {
        JsonNode message;
        try {
            message = json.read(line);
        } catch (RuntimeException e) {
            // System.out is redirected in the runner, so this indicates a protocol fault
            log.warn("runner wrote non-protocol output: {}", line.length() > 200 ? line.substring(0, 200) : line);
            return;
        }
        deliver(message, onMessage);
    }

    private static void deliver(JsonNode message, Consumer<JsonNode> onMessage) {
        try {
            onMessage.accept(message);
        } catch (RuntimeException e) {
            log.error("failed to handle runner message", e);
        }
    }

    ChildJvm.Launch launchConfig() {
        return new ChildJvm.Launch(javaExecutable(), classpath(), mainClass, props.activeProcessorCount(),
                props.stdoutCapBytes(), props.stderrCapBytes());
    }

    List<String> jvmArguments(int heapMb, int timeoutSeconds, Path workDir) {
        return jvmArguments(heapMb, timeoutSeconds, workDir, List.of());
    }

    List<String> jvmArguments(int heapMb, int timeoutSeconds, Path workDir, List<String> variantFlags) {
        return ChildJvm.jvmArguments(launchConfig(), heapMb, timeoutSeconds, workDir, variantFlags);
    }

    /** The JVM flags the run's variant declares in its descriptor (empty for almost every scenario). */
    static List<String> variantFlags(RunSpec spec) {
        return ScenarioCatalog.find(spec.scenarioId())
                .flatMap(s -> s.descriptor().variant(spec.variant()))
                .map(VariantSpec::jvmFlags)
                .orElse(List.of());
    }

    static List<String> vetVariantFlags(List<String> flags) {
        return ChildJvm.vetVariantFlags(flags);
    }

    /** Tests substitute a deliberately misbehaving main class to exercise the kill paths. */
    void useMainClassForTesting(String className) {
        this.mainClass = className;
    }

    String javaExecutable() {
        if (props.javaExecutable() != null && !props.javaExecutable().isBlank()) {
            return props.javaExecutable();
        }
        return ChildJvm.defaultJavaExecutable();
    }

    String classpath() {
        if (props.classpath() != null && !props.classpath().isBlank()) {
            return props.classpath();
        }
        return System.getProperty("java.class.path");
    }

    static String quoteArg(String arg) {
        return ChildJvm.quoteArg(arg);
    }
}
