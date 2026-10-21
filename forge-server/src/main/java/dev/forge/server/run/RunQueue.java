package dev.forge.server.run;

import dev.forge.lab.scenario.Lab;
import dev.forge.lab.scenario.RunSpec;
import dev.forge.lab.scenario.Scenario;
import dev.forge.lab.scenario.ScenarioCatalog;
import dev.forge.server.config.Json;
import dev.forge.server.config.RunnerProperties;
import dev.forge.server.lab.LabDatabaseService;
import dev.forge.server.system.HostInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Executes queued runs. The queue <em>is</em> the {@code run} table: workers claim rows with
 * {@code FOR UPDATE SKIP LOCKED}, so queued runs survive a server restart and are never executed
 * twice. By default there is a single worker, because concurrent benchmarks distort each other.
 */
@Component
public class RunQueue implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RunQueue.class);

    private final RunRepository runs;
    private final RunService service;
    private final RunnerLauncher launcher;
    private final RunnerProperties props;
    private final HostInfo host;
    private final Json json;
    private final LabDatabaseService labDatabase;
    private final Semaphore signal = new Semaphore(0);
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running;

    public RunQueue(RunRepository runs, RunService service, RunnerLauncher launcher, RunnerProperties props,
                    HostInfo host, Json json, LabDatabaseService labDatabase) {
        this.runs = runs;
        this.service = service;
        this.launcher = launcher;
        this.props = props;
        this.host = host;
        this.json = json;
        this.labDatabase = labDatabase;
        service.onEnqueue(() -> signal.release());
    }

    @Override
    public void start() {
        int orphaned = runs.failOrphanedRunning("server restarted while the run was executing; its runner was lost");
        if (orphaned > 0) {
            log.warn("marked {} orphaned RUNNING run(s) as FAILED", orphaned);
        }
        running = true;
        for (int i = 0; i < props.maxConcurrentRuns(); i++) {
            workers.add(Thread.ofPlatform().name("forge-run-worker-" + i).daemon().start(this::loop));
        }
        log.info("run queue started with {} worker(s)", props.maxConcurrentRuns());
    }

    @Override
    public void stop() {
        running = false;
        signal.release(workers.size());
        for (Thread t : workers) {
            t.interrupt();
        }
        workers.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            try {
                var next = runs.claimNext();
                if (next.isPresent()) {
                    execute(next.get());
                } else {
                    signal.tryAcquire(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("run worker error", e);
                sleepQuietly();
            }
        }
    }

    /** Mutable collection point for what the runner reports. */
    private static final class Collected {
        JsonNode result;
        JsonNode environment;
        String errorKind;
        String errorMessage;
        int artifacts;
    }

    void execute(RunRecord run) throws InterruptedException {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = json.convert(run.params(), Map.class);
        RunSpec spec = new RunSpec(run.id().toString(), run.scenarioId(), run.scenarioVersion(), run.variant(), params,
                run.seed(), run.trials(), run.warmupTrials());
        boolean needsLab = ScenarioCatalog.find(run.scenarioId()).map(Scenario::descriptor)
                .map(d -> d.lab() == Lab.DATABASE).orElse(false);
        if (needsLab) {
            try {
                spec = spec.withLabDatabase(labDatabase.ensure());
            } catch (RuntimeException e) {
                log.error("lab database unavailable for {}", run.id(), e);
                runs.addEvent(run.id(), Instant.now(), "ERROR", "lab database unavailable: " + e.getMessage());
                runs.finish(run.id(), RunStatus.FAILED, "lab database unavailable: " + e.getMessage(), null, null, null);
                return;
            }
        }
        runs.addEvent(run.id(), Instant.now(), "INFO", "starting runner JVM: heap " + run.heapMb() + " MB, timeout "
                + run.timeoutSeconds() + " s" + (props.activeProcessorCount() > 0
                ? ", ActiveProcessorCount=" + props.activeProcessorCount() : ""));
        Collected c = new Collected();
        RunnerLauncher.Outcome outcome;
        try {
            outcome = launcher.launch(spec, run.heapMb(), run.timeoutSeconds(), msg -> handle(run, msg, c),
                    () -> service.isCancelRequested(run.id()));
        } catch (java.io.IOException e) {
            log.error("could not start runner for {}", run.id(), e);
            runs.addEvent(run.id(), Instant.now(), "ERROR", "could not start runner: " + e.getMessage());
            runs.finish(run.id(), RunStatus.FAILED, "could not start runner: " + e.getMessage(), null, null, null);
            return;
        } finally {
            service.clearCancel(run.id());
        }

        if (!outcome.stderr().isBlank()) {
            runs.putArtifact(run.id(), "runner-stderr.txt", "text/plain",
                    outcome.stderr() + (outcome.stderrTruncated() ? "\n… truncated at " + props.stderrCapBytes() + " bytes" : ""));
        }

        RunStatus status;
        String reason;
        switch (outcome.termination()) {
            case TIMED_OUT -> {
                status = RunStatus.TIMED_OUT;
                reason = "exceeded the " + run.timeoutSeconds() + " s wall-clock limit; runner process tree destroyed";
            }
            case CANCELLED -> {
                status = RunStatus.CANCELLED;
                reason = "cancelled by user; runner process tree destroyed";
            }
            case OUTPUT_LIMIT -> {
                status = RunStatus.FAILED;
                reason = "runner output exceeded " + props.stdoutCapBytes() + " bytes; runner destroyed";
            }
            default -> {
                int code = outcome.exitCode();
                if (code == 0 && c.result != null) {
                    status = RunStatus.SUCCEEDED;
                    reason = null;
                } else if (code == 3) {
                    status = RunStatus.FAILED;
                    reason = "OutOfMemoryError: the scenario exceeded the " + run.heapMb() + " MB heap limit";
                } else if ("cancelled".equals(c.errorKind)) {
                    status = RunStatus.TIMED_OUT;
                    reason = "scenario stopped at its deadline: " + c.errorMessage;
                } else {
                    status = RunStatus.FAILED;
                    reason = c.errorMessage != null ? c.errorKind + ": " + c.errorMessage : "runner exited with code " + code;
                }
            }
        }
        if (needsLab) {
            try {
                labDatabase.dropRunSchema(run.id());
            } catch (RuntimeException e) {
                runs.addEvent(run.id(), Instant.now(), "WARN", "could not drop the run's lab schema: " + e.getMessage());
            }
        }
        runs.finish(run.id(), status, reason, outcome.exitCode(), environment(run, c, outcome, needsLab), c.result);
        runs.addEvent(run.id(), Instant.now(), status == RunStatus.SUCCEEDED ? "INFO" : "ERROR",
                "finished: " + status + (reason == null ? "" : " — " + reason));
    }

    private void handle(RunRecord run, JsonNode msg, Collected c) {
        String type = msg.path("type").asString("");
        Instant ts = msg.has("ts") ? Instant.ofEpochMilli(msg.get("ts").asLong()) : Instant.now();
        switch (type) {
            case "event" -> runs.addEvent(run.id(), ts, msg.path("level").asString("INFO"), msg.path("message").asString(""));
            case "progress" -> runs.progress(run.id(), msg.path("done").asLong(), msg.path("total").asLong(),
                    msg.path("phase").asString(null));
            case "artifact" -> {
                String name = sanitizeName(msg.path("name").asString("artifact.txt"));
                String content = msg.path("content").asString("");
                if (c.artifacts >= props.maxArtifactsPerRun()) {
                    runs.addEvent(run.id(), ts, "WARN", "artifact " + name + " dropped: per-run limit reached");
                } else if (content.length() > props.artifactCapBytes()) {
                    runs.putArtifact(run.id(), name, "text/plain", content.substring(0, props.artifactCapBytes())
                            + "\n… truncated at " + props.artifactCapBytes() + " characters");
                    c.artifacts++;
                } else {
                    runs.putArtifact(run.id(), name, "text/plain", content);
                    c.artifacts++;
                }
            }
            case "result" -> {
                c.result = msg.get("result");
                c.environment = msg.get("environment");
            }
            case "error" -> {
                c.errorKind = msg.path("kind").asString("error");
                c.errorMessage = msg.path("message").asString("");
                runs.addEvent(run.id(), ts, "ERROR", c.errorKind + ": " + c.errorMessage);
                String trace = msg.path("stackTrace").asString(null);
                if (trace != null && !trace.isBlank()) {
                    runs.putArtifact(run.id(), "runner-error.txt", "text/plain", trace);
                }
            }
            default -> log.warn("unknown runner message type '{}'", type);
        }
    }

    private JsonNode environment(RunRecord run, Collected c, RunnerLauncher.Outcome outcome, boolean needsLab) {
        Map<String, Object> launch = new LinkedHashMap<>();
        launch.put("heapMb", run.heapMb());
        launch.put("timeoutSeconds", run.timeoutSeconds());
        launch.put("activeProcessorCountCap", props.activeProcessorCount());
        launch.put("maxConcurrentRuns", props.maxConcurrentRuns());
        launch.put("executor", launcher.remote() ? "runner container (agent)" : "server process");
        if (needsLab) {
            Map<String, Object> lab = new LinkedHashMap<>(labDatabase.status());
            lab.keySet().retainAll(List.of("database", "role", "platformIsolated", "statementTimeoutSeconds", "lockTimeoutSeconds", "connectionLimit"));
            launch.put("labDatabase", lab);
        }
        launch.put("jvmFlags", outcome.commandLine().stream().filter(a -> a.startsWith("-X") || a.startsWith("-XX")).toList());
        ObjectNode env = json.mapper().createObjectNode();
        env.set("runner", c.environment);
        // the machine the run executed on: the runner container's when the agent ran it
        env.set("host", json.tree(outcome.host() != null ? outcome.host() : host.facts()));
        env.set("launch", json.tree(launch));
        return env;
    }

    static String sanitizeName(String name) {
        String clean = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.length() > 100 ? clean.substring(0, 100) : clean;
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
