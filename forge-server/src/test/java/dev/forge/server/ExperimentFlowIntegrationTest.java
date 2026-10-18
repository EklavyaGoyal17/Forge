package dev.forge.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: HTTP API → PostgreSQL → run queue → isolated runner JVM → stored results →
 * comparison, review and report. Requires Docker (skipped otherwise).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "forge.runner.default-heap-mb=128"})
class ExperimentFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper mapper;

    private final HttpClient http = HttpClient.newHttpClient();

    private record Response(int status, JsonNode body, String raw) {
    }

    private Response call(String method, String path, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json = null;
        if (r.headers().firstValue("Content-Type").orElse("").contains("json") && !r.body().isBlank()) {
            json = mapper.readTree(r.body());
        }
        return new Response(r.statusCode(), json, r.body());
    }

    private JsonNode awaitTerminal(String runId, Duration max) throws Exception {
        long deadline = System.nanoTime() + max.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode run = call("GET", "/api/runs/" + runId, null).body().get("run");
            String status = run.get("status").asString();
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) {
                return run;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("run " + runId + " did not finish within " + max);
    }

    private String createExperiment(String scenarioId) throws Exception {
        Response r = call("POST", "/api/experiments", Map.of(
                "title", "Lost updates under contention",
                "question", "Does CAS preserve every deposit, and what does it cost?",
                "hypothesis", "atomic-cas holds the invariant; unsynchronized loses updates",
                "scenarioId", scenarioId,
                "tags", java.util.List.of("concurrency", "demo")));
        assertThat(r.status()).isEqualTo(201);
        return r.body().get("id").asString();
    }

    @Test
    void fullExperimentLifecycle() throws Exception {
        assertThat(call("GET", "/api/scenarios", null).body().size()).isGreaterThanOrEqualTo(4);
        JsonNode scenario = call("GET", "/api/scenarios/concurrency.lost-update", null).body();
        assertThat(scenario.get("sources").get("unsynchronized").asString()).contains("balance = current + 1");

        String exp = createExperiment("concurrency.lost-update");

        Map<String, Object> params = Map.of("mode", "stress", "threads", 4, "depositsPerThread", 20_000);
        Response baseline = call("POST", "/api/experiments/" + exp + "/runs", Map.of(
                "variant", "unsynchronized", "params", params, "trials", 5, "warmupTrials", 1, "role", "BASELINE", "seed", 42));
        assertThat(baseline.status()).as(baseline.raw()).isEqualTo(202);
        Response candidate = call("POST", "/api/experiments/" + exp + "/runs", Map.of(
                "variant", "atomic-cas", "params", params, "trials", 5, "warmupTrials", 1, "role", "CANDIDATE", "seed", 42));
        assertThat(candidate.status()).isEqualTo(202);

        JsonNode b = awaitTerminal(baseline.body().get("id").asString(), Duration.ofSeconds(90));
        JsonNode c = awaitTerminal(candidate.body().get("id").asString(), Duration.ofSeconds(90));
        assertThat(b.get("status").asString()).as(b.toString()).isEqualTo("SUCCEEDED");
        assertThat(c.get("status").asString()).as(c.toString()).isEqualTo("SUCCEEDED");
        assertThat(c.get("result").get("trials").size()).isEqualTo(5);
        assertThat(c.get("result").get("invariant").get("held").asBoolean()).isTrue();
        assertThat(c.get("environment").at("/runner/javaVersion").asString()).isNotBlank();
        assertThat(c.get("environment").at("/launch/heapMb").asInt()).isEqualTo(128);
        assertThat(c.get("environment").at("/runner/jvmArguments").toString()).contains("-Xmx128m");

        JsonNode view = call("GET", "/api/runs/" + c.get("id").asString(), null).body();
        assertThat(view.get("summaries").get("throughputMopsPerSec").get("n").asInt()).isEqualTo(5);

        JsonNode events = call("GET", "/api/runs/" + c.get("id").asString() + "/events", null).body();
        assertThat(events.toString()).contains("queued").contains("runner started").contains("finished: SUCCEEDED");

        JsonNode cmp = call("GET", "/api/compare?baseline=" + b.get("id").asString() + "&candidate=" + c.get("id").asString(), null).body();
        assertThat(cmp.get("primaryMetric").asString()).isEqualTo("throughputMopsPerSec");
        assertThat(cmp.get("metrics").get(0).get("primary").asBoolean()).isTrue();
        assertThat(cmp.get("parameterDifferences").size()).isZero();
        assertThat(cmp.get("candidateInvariantHeld").asBoolean()).isTrue();

        Response patch = call("PATCH", "/api/experiments/" + exp, Map.of(
                "observations", "CAS kept every deposit.",
                "decision", "Use AtomicLong.",
                "reviewAnswers", Map.of("noise", "CI excludes 1.0")));
        assertThat(patch.status()).isEqualTo(200);

        JsonNode review = call("GET", "/api/experiments/" + exp + "/review", null).body();
        assertThat(review.get("applicable").asInt()).isPositive();
        assertThat(review.get("checks").toString()).contains("isolated-change").contains("correctness");

        Response report = call("GET", "/api/experiments/" + exp + "/report.md", null);
        assertThat(report.status()).isEqualTo(200);
        assertThat(report.raw()).contains("# Lost updates under contention")
                .contains("## Comparison")
                .contains("## Engineering Review Board")
                .contains("CAS kept every deposit.")
                .contains("CI excludes 1.0");

        JsonNode list = call("GET", "/api/experiments?q=contention", null).body();
        assertThat(list.size()).isGreaterThanOrEqualTo(1);
        assertThat(list.get(0).get("runCount").asInt()).isEqualTo(2);
    }

    @Test
    void invalidInputIsRejectedWithFieldErrors() throws Exception {
        String exp = createExperiment("concurrency.lost-update");
        Response r = call("POST", "/api/experiments/" + exp + "/runs", Map.of(
                "variant", "unsynchronized", "params", Map.of("threads", 9999, "evil", 1), "heapMb", 99_999));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("fieldErrors").toString()).contains("params.threads").contains("params.evil").contains("heapMb");

        assertThat(call("PATCH", "/api/experiments/" + exp, Map.of("id", "x")).status()).isEqualTo(400);
        assertThat(call("POST", "/api/experiments", Map.of("title", "t", "scenarioId", "concurrency.lost-update")).status())
                .as("hypothesis is required").isEqualTo(400);
        assertThat(call("GET", "/api/experiments/00000000-0000-0000-0000-000000000000", null).status()).isEqualTo(404);
    }

    @Test
    void runThatOutlivesItsTimeoutIsStoppedAndRecorded() throws Exception {
        String exp = createExperiment("concurrency.lost-update");
        Response r = call("POST", "/api/experiments/" + exp + "/runs", Map.of(
                "variant", "synchronized",
                "params", Map.of("mode", "stress", "threads", 64, "depositsPerThread", 5_000_000),
                "trials", 200, "warmupTrials", 0, "timeoutSeconds", 6));
        assertThat(r.status()).isEqualTo(202);
        JsonNode run = awaitTerminal(r.body().get("id").asString(), Duration.ofSeconds(60));
        assertThat(run.get("status").asString()).isEqualTo("TIMED_OUT");
        assertThat(run.get("statusReason").asString()).isNotBlank();
    }

    @Test
    void runningRunCanBeCancelled() throws Exception {
        String exp = createExperiment("concurrency.lost-update");
        Response r = call("POST", "/api/experiments/" + exp + "/runs", Map.of(
                "variant", "synchronized",
                "params", Map.of("mode", "stress", "threads", 64, "depositsPerThread", 5_000_000),
                "trials", 200, "warmupTrials", 0, "timeoutSeconds", 120));
        String id = r.body().get("id").asString();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!call("GET", "/api/runs/" + id, null).body().at("/run/status").asString().equals("RUNNING")) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(100);
        }
        assertThat(call("POST", "/api/runs/" + id + "/cancel", null).status()).isEqualTo(200);
        JsonNode run = awaitTerminal(id, Duration.ofSeconds(30));
        assertThat(run.get("status").asString()).isEqualTo("CANCELLED");
    }
}
