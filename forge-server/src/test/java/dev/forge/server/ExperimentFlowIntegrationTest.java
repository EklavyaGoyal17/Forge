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
import java.util.List;
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
    void pinnedBaselineGatesEveryOtherRun() throws Exception {
        String exp = createExperiment("concurrency.lost-update");
        Map<String, Object> params = Map.of("mode", "stress", "threads", 2, "depositsPerThread", 10_000);
        String first = call("POST", "/api/experiments/" + exp + "/runs", Map.of(
                "variant", "atomic-cas", "params", params, "trials", 5, "warmupTrials", 0, "role", "BASELINE")).body().get("id").asString();
        String same = call("POST", "/api/experiments/" + exp + "/runs", Map.of(
                "variant", "long-adder", "params", params, "trials", 5, "warmupTrials", 0, "role", "CANDIDATE")).body().get("id").asString();
        String other = call("POST", "/api/experiments/" + exp + "/runs", Map.of(
                "variant", "long-adder", "params", Map.of("mode", "stress", "threads", 3, "depositsPerThread", 10_000),
                "trials", 5, "warmupTrials", 0, "role", "EXPLORATORY")).body().get("id").asString();
        for (String id : List.of(first, same, other)) {
            assertThat(awaitTerminal(id, Duration.ofSeconds(90)).get("status").asString()).isEqualTo("SUCCEEDED");
        }

        JsonNode none = call("GET", "/api/experiments/" + exp + "/regressions", null).body();
        assertThat(none.get("baselineRunId").isNull()).isTrue();
        assertThat(none.get("checks").size()).isZero();

        // only a SUCCEEDED run of this experiment can be pinned
        String foreign = createExperiment("concurrency.lost-update");
        assertThat(call("PUT", "/api/experiments/" + foreign + "/pinned-baseline", Map.of("runId", first)).status()).isEqualTo(400);

        Response pinned = call("PUT", "/api/experiments/" + exp + "/pinned-baseline", Map.of("runId", first));
        assertThat(pinned.status()).as(pinned.raw()).isEqualTo(200);
        assertThat(pinned.body().get("pinnedBaselineRunId").asString()).isEqualTo(first);

        JsonNode report = call("GET", "/api/experiments/" + exp + "/regressions", null).body();
        assertThat(report.get("baselineRunId").asString()).isEqualTo(first);
        assertThat(report.get("primaryMetric").asString()).isEqualTo("throughputMopsPerSec");
        assertThat(report.get("checks").size()).isEqualTo(2);
        JsonNode sameCheck = report.get("checks").get(0);
        JsonNode otherCheck = report.get("checks").get(1);
        assertThat(sameCheck.get("runId").asString()).isEqualTo(same);
        // timing-dependent: any statistical verdict is acceptable, but never "not comparable"
        assertThat(sameCheck.get("gate").asString()).isIn("REGRESSION", "IMPROVEMENT", "NO_DIFFERENCE_DETECTED");
        assertThat(otherCheck.get("gate").asString()).isEqualTo("NOT_COMPARABLE");
        assertThat(otherCheck.get("reasons").toString()).contains("threads");

        assertThat(call("GET", "/api/experiments/" + exp + "/report.md", null).raw())
                .contains("## Regression gate: pinned baseline run 1 (atomic-cas)").contains("NOT_COMPARABLE");

        Response unpinned = call("DELETE", "/api/experiments/" + exp + "/pinned-baseline", null);
        assertThat(unpinned.body().get("pinnedBaselineRunId").isNull()).isTrue();
        assertThat(call("GET", "/api/experiments/" + exp + "/regressions", null).body().get("checks").size()).isZero();

        // an experiment with a pinned run can still be deleted (run → experiment FK cascades, pin is set null)
        call("PUT", "/api/experiments/" + exp + "/pinned-baseline", Map.of("runId", first));
        assertThat(call("DELETE", "/api/experiments/" + exp, null).status()).isEqualTo(204);
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
