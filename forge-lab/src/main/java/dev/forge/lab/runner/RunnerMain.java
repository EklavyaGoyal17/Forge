package dev.forge.lab.runner;

import dev.forge.lab.scenario.LabDatabase;
import dev.forge.lab.scenario.ParameterValidator;
import dev.forge.lab.scenario.RunContext;
import dev.forge.lab.scenario.RunSpec;
import dev.forge.lab.scenario.Scenario;
import dev.forge.lab.scenario.ScenarioCatalog;
import dev.forge.lab.scenario.ScenarioResult;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point of the isolated runner JVM.
 *
 * <p>Reads one {@link RunSpec} JSON document from stdin, re-validates it, executes the scenario,
 * and writes JSON-lines {@link Protocol} messages to the real stdout. {@code System.out} is
 * redirected to stderr first, so nothing a scenario or library prints can corrupt the protocol.
 *
 * <p>System properties set by the launcher: {@code forge.workDir} (private run directory) and
 * {@code forge.timeoutMillis} (the scenario must finish before this; the server kills the process
 * shortly after).
 */
public final class RunnerMain {

    public static final int EXIT_OK = 0;
    public static final int EXIT_BAD_SPEC = 2;
    // 3 is produced by the JVM itself under -XX:+ExitOnOutOfMemoryError
    public static final int EXIT_SCENARIO_FAILED = 4;
    public static final int EXIT_CANCELLED = 5;

    private static final int MAX_SPEC_BYTES = 1 << 20;

    private RunnerMain() {
    }

    public static void main(String[] args) {
        PrintStream protocolOut = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err);
        Protocol protocol = new Protocol(protocolOut);
        int code = run(protocol, System.in);
        protocolOut.flush();
        // exit explicitly: scenarios may leave daemon threads spinning on purpose (visibility demo)
        System.exit(code);
    }

    static int run(Protocol protocol, InputStream stdin) {
        RunSpec spec;
        Scenario scenario;
        try {
            byte[] bytes = stdin.readNBytes(MAX_SPEC_BYTES + 1);
            if (bytes.length > MAX_SPEC_BYTES) {
                protocol.error("bad-spec", "run spec exceeds " + MAX_SPEC_BYTES + " bytes", null);
                return EXIT_BAD_SPEC;
            }
            RunSpec raw = Protocol.MAPPER.readValue(new String(bytes, StandardCharsets.UTF_8), RunSpec.class);
            scenario = ScenarioCatalog.find(raw.scenarioId()).orElse(null);
            if (scenario == null) {
                protocol.error("bad-spec", "unknown scenario " + raw.scenarioId(), null);
                return EXIT_BAD_SPEC;
            }
            if (scenario.descriptor().version() != raw.scenarioVersion()) {
                protocol.error("bad-spec", "scenario version mismatch: runner has v" + scenario.descriptor().version()
                        + ", spec requests v" + raw.scenarioVersion(), null);
                return EXIT_BAD_SPEC;
            }
            ParameterValidator.Result v = ParameterValidator.validate(scenario.descriptor(), raw.variant(),
                    raw.params(), raw.trials(), raw.warmupTrials());
            if (!v.ok()) {
                protocol.error("bad-spec", "invalid parameters: " + v.errors(), null);
                return EXIT_BAD_SPEC;
            }
            spec = new RunSpec(raw.runId(), raw.scenarioId(), raw.scenarioVersion(), raw.variant(), v.params(),
                    raw.seed(), v.trials(), v.warmupTrials(), raw.labDatabase());
        } catch (IOException | RuntimeException e) {
            protocol.error("bad-spec", "could not read run spec: " + e.getMessage(), stackTrace(e));
            return EXIT_BAD_SPEC;
        }

        Path workDir = Path.of(System.getProperty("forge.workDir", System.getProperty("java.io.tmpdir")));
        long timeoutMillis = Long.getLong("forge.timeoutMillis", 60_000L);
        // leave a margin so the scenario can report before the server's hard kill
        long deadline = System.nanoTime() + Math.max(1_000, timeoutMillis - 2_000) * 1_000_000L;
        RunContext ctx = new ProtocolContext(protocol, workDir, deadline, spec.labDatabase());

        try {
            Files.createDirectories(workDir);
            var environment = EnvironmentCapture.capture();
            protocol.event("INFO", "runner started: " + spec.scenarioId() + " / " + spec.variant()
                    + ", seed " + spec.seed() + ", pid " + environment.get("pid"));
            long start = System.nanoTime();
            ScenarioResult result = scenario.run(spec, ctx);
            protocol.event("INFO", String.format("scenario finished in %.1f ms", (System.nanoTime() - start) / 1e6));
            protocol.result(environment, result);
            return EXIT_OK;
        } catch (RunContext.CancelledException e) {
            protocol.error("cancelled", e.getMessage(), null);
            return EXIT_CANCELLED;
        } catch (Throwable t) {
            protocol.error("scenario-failed", t.toString(), stackTrace(t));
            return EXIT_SCENARIO_FAILED;
        }
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 16_000 ? s.substring(0, 16_000) + "\n…" : s;
    }

    private record ProtocolContext(Protocol protocol, Path workDir, long deadlineNanos, LabDatabase lab) implements RunContext {
        @Override
        public java.util.Optional<LabDatabase> labDatabase() {
            return java.util.Optional.ofNullable(lab);
        }

        @Override
        public void event(String level, String message) {
            protocol.event(level, message);
        }

        @Override
        public void progress(long done, long total, String phase) {
            protocol.progress(done, total, phase);
        }

        @Override
        public void artifact(String name, String contentType, String content) {
            protocol.artifact(name, contentType, content);
        }
    }
}
