package dev.forge.server.run;

import dev.forge.lab.runner.ChildJvm;
import dev.forge.lab.runner.RunnerAgent;
import dev.forge.lab.runner.RunnerMain;
import dev.forge.lab.scenario.RunSpec;
import dev.forge.server.config.Json;
import dev.forge.server.config.RunnerProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The server driving runs through the runner agent (the dedicated runner container's process),
 * started here in-process on loopback. The same kill paths as {@link RunnerLauncherTest} must hold
 * on the agent's side, and the agent must refuse requests it should not trust.
 */
class RunnerAgentTest {

    private static final String TOKEN = "test-token-0123456789";

    @TempDir
    Path root;

    private RunnerAgent agent;

    private RunnerAgent startAgent(String mainClass, long stdoutCap) throws Exception {
        ChildJvm.Launch launch = new ChildJvm.Launch(ChildJvm.defaultJavaExecutable(), System.getProperty("java.class.path"), mainClass, 0,
                stdoutCap, 65_536);
        agent = new RunnerAgent(new RunnerAgent.Config("127.0.0.1", 0, TOKEN, launch, root.resolve("agent"), 512, 120, 1, false, "test"));
        agent.start();
        return agent;
    }

    @AfterEach
    void stop() {
        if (agent != null) {
            agent.stop();
        }
    }

    private RunnerLauncher serverFor(RunnerAgent a) throws Exception {
        RunnerProperties props = new RunnerProperties("", "", root.resolve("server").toString(), 1, 10, 128, 512, 60, 0,
                1 << 20, 65_536, 1_048_576, 20, false, "http://127.0.0.1:" + a.port(), TOKEN);
        return new RunnerLauncher(props, new Json(JsonMapper.builder().build()));
    }

    private static RunSpec spec(String id) {
        return new RunSpec(id, "concurrency.lost-update", 1, "atomic-cas",
                Map.of("mode", "stress", "threads", 2L, "depositsPerThread", 100L), 1, 2, 0);
    }

    @Test
    void aRealRunThroughTheAgentStreamsTheProtocolAndReportsTheAgentsHost() throws Exception {
        RunnerLauncher server = serverFor(startAgent(RunnerMain.class.getName(), 1 << 20));
        assertThat(server.remote()).isTrue();
        List<JsonNode> messages = new CopyOnWriteArrayList<>();
        RunnerLauncher.Outcome o = server.launch(spec("11111111-2222-3333-4444-555555555555"), 128, 60, messages::add, () -> false);
        assertThat(o.termination()).isEqualTo(RunnerLauncher.Termination.EXITED);
        assertThat(o.exitCode()).isZero();
        assertThat(messages).anySatisfy(m -> assertThat(m.path("type").asString()).isEqualTo("result"));
        assertThat(o.host()).containsKeys("cpuModel", "logicalProcessors", "osName");
        assertThat(o.commandLine()).contains("-Xmx128m");
        assertThat(Files.exists(root.resolve("agent").resolve("run-11111111-2222-3333-4444-555555555555"))).isFalse();
    }

    @Test
    void theAgentEnforcesTheTimeoutAndKillsTheProcessTree() throws Exception {
        RunnerLauncher server = serverFor(startAgent(MisbehavingRunners.HangForever.class.getName(), 1 << 20));
        List<JsonNode> messages = new CopyOnWriteArrayList<>();
        long start = System.nanoTime();
        RunnerLauncher.Outcome o = server.launch(spec("hang"), 128, 5, messages::add, () -> false);
        assertThat(o.termination()).isEqualTo(RunnerLauncher.Termination.TIMED_OUT);
        assertThat((System.nanoTime() - start) / 1e9).isBetween(4.5, 20.0);
        long grandchild = Long.parseLong(messages.stream().map(m -> m.path("message").asString())
                .filter(s -> s.startsWith("hanging ")).findFirst().orElseThrow().substring("hanging ".length()));
        Thread.sleep(500);
        assertThat(ProcessHandle.of(grandchild).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void cancellationFromTheServerReachesTheAgent() throws Exception {
        RunnerLauncher server = serverFor(startAgent(MisbehavingRunners.HangForever.class.getName(), 1 << 20));
        long start = System.nanoTime();
        RunnerLauncher.Outcome o = server.launch(spec("cancel"), 128, 60, m -> { }, () -> System.nanoTime() - start > 1_500_000_000L);
        assertThat(o.termination()).isEqualTo(RunnerLauncher.Termination.CANCELLED);
        assertThat((System.nanoTime() - start) / 1e9).isLessThan(20.0);
    }

    @Test
    void theAgentsOutputCapApplies() throws Exception {
        RunnerLauncher server = serverFor(startAgent(MisbehavingRunners.Flood.class.getName(), 200_000));
        assertThat(server.launch(spec("flood"), 128, 30, m -> { }, () -> false).termination()).isEqualTo(RunnerLauncher.Termination.OUTPUT_LIMIT);
    }

    @Test
    void theAgentRefusesUnauthenticatedOrOutOfBoundsRequests() throws Exception {
        RunnerAgent a = startAgent(RunnerMain.class.getName(), 1 << 20);
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        URI runs = URI.create("http://127.0.0.1:" + a.port() + "/runs");
        String body = "{\"runId\":\"x\",\"heapMb\":128,\"timeoutSeconds\":10,\"spec\":{}}";
        assertThat(http.send(HttpRequest.newBuilder(runs).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
        assertThat(http.send(HttpRequest.newBuilder(runs).header("X-Forge-Runner-Token", "wrong-token-0123456789")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
        // heap above the agent's own ceiling, and a run id that is not an identifier
        for (String bad : List.of("{\"runId\":\"x\",\"heapMb\":4096,\"timeoutSeconds\":10,\"spec\":{}}",
                "{\"runId\":\"../etc\",\"heapMb\":128,\"timeoutSeconds\":10,\"spec\":{}}",
                "{\"runId\":\"x\",\"heapMb\":128,\"timeoutSeconds\":100000,\"spec\":{}}")) {
            assertThat(http.send(HttpRequest.newBuilder(runs).header("X-Forge-Runner-Token", TOKEN)
                    .POST(HttpRequest.BodyPublishers.ofString(bad)).build(), HttpResponse.BodyHandlers.discarding()).statusCode())
                    .as(bad).isEqualTo(400);
        }
        assertThat(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + a.port() + "/health")).build(),
                HttpResponse.BodyHandlers.ofString()).body()).isEqualTo("ok");
    }

    @Test
    void aServerConfiguredForAnAgentWithoutATokenDoesNotStart() {
        assertThatThrownBy(() -> new RunnerProperties("", "", root.toString(), 1, 10, 128, 512, 60, 0, 1 << 20, 65_536, 1_048_576, 20, false,
                "http://runner:8095", ""))
                .hasMessageContaining("agent-token");
    }
}
