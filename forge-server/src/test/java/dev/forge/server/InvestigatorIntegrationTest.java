package dev.forge.server;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The investigator end to end: real runs → evidence bundle → rule-based findings → a question
 * answered through the Anthropic provider (the official SDK, pointed at a local stub that plays the
 * Messages API, so no key or network is needed) → the answer checked against the evidence → stored.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "forge.runner.default-heap-mb=128", "forge.lab-database.enabled=false",
                "forge.ai.provider=anthropic", "forge.ai.api-key=test-key"})
class InvestigatorIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    record Captured(String path, Map<String, List<String>> headers, String body) {
    }

    static final List<Captured> REQUESTS = new CopyOnWriteArrayList<>();
    static volatile String reply = "";
    static final HttpServer STUB = startStub();

    static HttpServer startStub() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            s.createContext("/", ex -> {
                REQUESTS.add(new Captured(ex.getRequestURI().getPath(), Map.copyOf(ex.getRequestHeaders()),
                        new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                String json = "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\","
                        + "\"content\":[{\"type\":\"text\",\"text\":" + JsonMapper.builder().build().writeValueAsString(reply) + "}],"
                        + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":4321,\"output_tokens\":87}}";
                byte[] out = json.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry r) {
        r.add("forge.ai.base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        STUB.stop(0);
    }

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper mapper;

    private final HttpClient http = HttpClient.newHttpClient();

    private JsonNode call(String method, String path, Object body, int expected) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return r.body().isBlank() ? null : mapper.readTree(r.body());
    }

    private void await(String runId) throws Exception {
        for (int i = 0; i < 400; i++) {
            String s = call("GET", "/api/runs/" + runId, null, 200).get("run").get("status").asString();
            if (!s.equals("QUEUED") && !s.equals("RUNNING")) {
                assertThat(s).isEqualTo("SUCCEEDED");
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("run did not finish");
    }

    @Test
    void evidenceFindingsAndACheckedModelAnswer() throws Exception {
        JsonNode status = call("GET", "/api/investigator/status", null, 200);
        assertThat(status.get("provider").asString()).isEqualTo("anthropic");
        assertThat(status.get("model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(status.get("sendsEvidenceOffHost").asBoolean()).isTrue();
        assertThat(status.toString()).doesNotContain("test-key");

        String exp = call("POST", "/api/experiments", Map.of("title", "Lost updates", "question", "Does CAS keep every deposit?",
                "hypothesis", "atomic-cas holds the invariant", "scenarioId", "concurrency.lost-update"), 201).get("id").asString();
        Map<String, Object> params = Map.of("mode", "stress", "threads", 2, "depositsPerThread", 5_000);
        String b = call("POST", "/api/experiments/" + exp + "/runs", Map.of("variant", "synchronized", "params", params, "trials", 3,
                "warmupTrials", 0, "role", "BASELINE", "seed", 1), 202).get("id").asString();
        String c = call("POST", "/api/experiments/" + exp + "/runs", Map.of("variant", "atomic-cas", "params", params, "trials", 3,
                "warmupTrials", 0, "role", "CANDIDATE", "seed", 1), 202).get("id").asString();
        await(b);
        await(c);

        JsonNode analysis = call("GET", "/api/experiments/" + exp + "/investigation", null, 200);
        Set<String> ids = new HashSet<>();
        analysis.get("evidence").forEach(i -> ids.add(i.get("id").asString()));
        assertThat(ids).contains("Q", "S", "R1", "R1.env", "R1.m.throughputMopsPerSec", "R1.inv", "R2", "C1-2", "B");
        List<String> rules = new ArrayList<>();
        analysis.get("findings").forEach(f -> {
            rules.add(f.get("rule").asString());
            f.get("citations").forEach(x -> assertThat(ids).contains(x.asString()));   // every citation resolves
        });
        assertThat(rules).contains("primary-metric", "small-sample", "explore-mode", "scenario-caveats");

        reply = "CAS kept every deposit [R2.inv]; median throughput 123456.7 Mops/s [R2.m.throughputMopsPerSec] [R9.m.nothing].";
        JsonNode answer = call("POST", "/api/experiments/" + exp + "/investigation/questions", Map.of("question", "Did CAS keep every deposit?"), 201);
        assertThat(answer.get("provider").asString()).isEqualTo("anthropic");
        assertThat(answer.get("model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(answer.get("check").get("unknownCitations").toString()).contains("R9.m.nothing");
        assertThat(answer.get("check").get("unverifiedNumbers").toString()).contains("123456.7");
        assertThat(answer.get("check").get("inputTokens").asLong()).isEqualTo(4321);

        Captured req = REQUESTS.getLast();
        assertThat(req.path()).isEqualTo("/v1/messages");
        assertThat(header(req, "x-api-key")).isEqualTo("test-key");
        assertThat(header(req, "anthropic-beta")).contains("server-side-fallback-2026-07-01");
        JsonNode body = mapper.readTree(req.body());
        assertThat(body.get("model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(body.get("fallbacks").asString()).isEqualTo("default");
        assertThat(body.get("output_config").get("effort").asString()).isEqualTo("high");
        assertThat(body.toString()).contains("cite the evidence ids");
        String user = body.get("messages").get(0).get("content").toString();
        assertThat(user).contains("EVIDENCE").contains("[R1.m.throughputMopsPerSec]").contains("RULE-BASED FINDINGS").contains("Did CAS keep every deposit?");

        JsonNode history = call("GET", "/api/experiments/" + exp + "/investigation/questions", null, 200);
        assertThat(history.size()).isEqualTo(1);
        call("POST", "/api/experiments/" + exp + "/investigation/questions", Map.of("question", " "), 400);
    }

    private static String header(Captured c, String name) {
        return c.headers().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).findFirst()
                .map(e -> String.join(",", e.getValue())).orElse(null);
    }
}
