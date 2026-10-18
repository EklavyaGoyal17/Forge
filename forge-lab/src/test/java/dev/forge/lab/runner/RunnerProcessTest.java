package dev.forge.lab.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs {@link RunnerMain} as a real child process, exactly as the server does. */
class RunnerProcessTest {

    @TempDir
    Path work;

    private record Outcome(int exitCode, List<JsonNode> messages, String stderr) {
        JsonNode first(String type) {
            return messages.stream().filter(m -> m.get("type").asString().equals(type)).findFirst().orElse(null);
        }
    }

    private Outcome launch(String specJson) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(javaBin, "-Xmx128m", "-XX:+ExitOnOutOfMemoryError",
                "-Dforge.workDir=" + work, "-Dforge.timeoutMillis=60000",
                "-cp", System.getProperty("java.class.path"), RunnerMain.class.getName());
        pb.redirectError(work.resolve("stderr.txt").toFile());
        Process p = pb.start();
        try (OutputStream in = p.getOutputStream()) {
            in.write(specJson.getBytes(StandardCharsets.UTF_8));
        }
        List<JsonNode> messages = new ArrayList<>();
        try (var reader = p.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                messages.add(Protocol.MAPPER.readTree(line));
            }
        }
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Outcome(p.exitValue(), messages,
                Files.readString(work.resolve("stderr.txt")));
    }

    @Test
    void runsScenarioAndEmitsResultWithEnvironment() throws Exception {
        Outcome o = launch("""
                {"runId":"r1","scenarioId":"concurrency.lost-update","scenarioVersion":1,"variant":"atomic-cas",
                 "params":{"mode":"stress","threads":2,"depositsPerThread":1000},"seed":7,"trials":3,"warmupTrials":1}
                """);
        assertThat(o.exitCode()).as(o.stderr()).isEqualTo(RunnerMain.EXIT_OK);
        JsonNode result = o.first(Protocol.RESULT);
        assertThat(result).isNotNull();
        assertThat(result.get("result").get("trials")).hasSize(3);
        assertThat(result.get("result").get("invariant").get("held").asBoolean()).isTrue();
        assertThat(result.get("environment").get("javaVersion").asString()).isNotBlank();
        assertThat(result.get("environment").get("jvmArguments").toString()).contains("-Xmx128m");
        assertThat(o.first(Protocol.PROGRESS)).isNotNull();
    }

    @Test
    void rejectsInvalidSpecWithoutRunningAnything() throws Exception {
        Outcome o = launch("""
                {"runId":"r2","scenarioId":"concurrency.lost-update","scenarioVersion":1,"variant":"atomic-cas",
                 "params":{"threads":100000},"seed":7,"trials":3,"warmupTrials":0}
                """);
        assertThat(o.exitCode()).isEqualTo(RunnerMain.EXIT_BAD_SPEC);
        assertThat(o.first(Protocol.ERROR).get("message").asString()).contains("params.threads");
        assertThat(o.first(Protocol.RESULT)).isNull();
    }

    @Test
    void rejectsUnknownScenarioAndGarbage() throws Exception {
        assertThat(launch("{\"scenarioId\":\"../../etc/passwd\",\"scenarioVersion\":1}").exitCode())
                .isEqualTo(RunnerMain.EXIT_BAD_SPEC);
        assertThat(launch("not json").exitCode()).isEqualTo(RunnerMain.EXIT_BAD_SPEC);
    }
}
