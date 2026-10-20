package dev.forge.server.investigator;

import com.sun.net.httpserver.HttpServer;
import dev.forge.server.investigator.Evidence.Finding;
import dev.forge.server.investigator.Evidence.Item;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class InvestigatorUnitTest {

    private static final Evidence.Bundle BUNDLE = new Evidence.Bundle(UUID.randomUUID(), "performance.thread-pool-starvation", "Pool starvation", List.of(
            new Item("Q", "EXPERIMENT", "Question and hypothesis", null, "Question: why is p99 so high?\nHypothesis: the pool is too small"),
            new Item("R1.m.latencyP99Ms", "METRIC", "Run #1 Client p99 latency (primary)", 1,
                    "latencyP99Ms [ms] over 5 trials: median 1,998, mean 1997.4, stddev 3.21, min 1990, max 2001, CV 0.1607%"),
            new Item("R1.m.latencyP50Ms", "METRIC", "Run #1 Client p50 latency", 1, "latencyP50Ms [ms] over 5 trials: median 904.3"),
            new Item("R2.m.latencyP99Ms", "METRIC", "Run #2 Client p99 latency (primary)", 2, "latencyP99Ms [ms] over 5 trials: median 24.53")));

    // ------------------------------------------------------------------ answer checking

    @Test
    void unknownCitationsAndNumbersNotInTheEvidenceAreReported() {
        String answer = "p99 fell from 1998 ms [R1.m.latencyP99Ms] to 24.5 ms [R2.m.latencyP99Ms], about 81.4× [R7.m.latencyP99Ms]; "
                + "the queue held 12345 requests.";
        AnswerChecker.Result r = AnswerChecker.check(answer, BUNDLE);
        assertThat(r.cited()).containsExactly("R1.m.latencyP99Ms", "R2.m.latencyP99Ms", "R7.m.latencyP99Ms");
        assertThat(r.unknownCitations()).containsExactly("R7.m.latencyP99Ms");
        // 1998 is in the evidence ("1,998"), 24.5 is 24.53 rounded to the answer's precision;
        // 81.4 is derived (1998 / 24.53) and 12345 is invented — both must be checked by the reader
        assertThat(r.unverifiedNumbers()).containsExactly("81.4", "12345");
        assertThat(r.checkedNumbers()).isEqualTo(4);
    }

    @Test
    void smallWholeNumbersAndNumbersInsideCitationsAreNotChecked() {
        AnswerChecker.Result r = AnswerChecker.check("Across 3 trials [R1.m.latencyP99Ms] and 2 runs the median was 904.3 ms.", BUNDLE);
        assertThat(r.unverifiedNumbers()).isEmpty();
        assertThat(r.checkedNumbers()).isEqualTo(1);
    }

    @Test
    void roundingToTheAnswersPrecisionOrWithinHalfAPercentIsSupported() {
        List<BigDecimal> evidence = List.of(new BigDecimal("904.3"), new BigDecimal("1997.4"));
        assertThat(AnswerChecker.supported(new BigDecimal("904"), evidence)).isTrue();
        assertThat(AnswerChecker.supported(new BigDecimal("1997"), evidence)).isTrue();
        assertThat(AnswerChecker.supported(new BigDecimal("2000"), evidence)).isTrue();   // within 0.5%
        assertThat(AnswerChecker.supported(new BigDecimal("2020"), evidence)).isFalse();
    }

    // ------------------------------------------------------------------ rule-based answers

    @Test
    void withoutAProviderTheAnswerSaysSoAndRetrievesRelatedFindingsAndEvidence() {
        List<Finding> findings = List.of(
                new Finding("OBSERVATION", "heavy-tail", "Run #1: median latencyP99Ms is 2.209× latencyP50Ms (1998 vs 904.3).", List.of("R1.m.latencyP99Ms", "R1.m.latencyP50Ms")),
                new Finding("NEXT_EXPERIMENT", "rate-sweep", "Sweep the offered rate to find the knee.", List.of("R1.m.latencyP99Ms")),
                new Finding("CAVEAT", "scenario-caveats", "This scenario has 9 documented limitations.", List.of("S")));
        InvestigatorService.RuleAnswer a = InvestigatorService.ruleAnswer("Why is the p99 latency so much worse than p50?", BUNDLE, findings);
        assertThat(a.text()).startsWith("No AI provider is configured");
        assertThat(a.text()).contains("Observations").contains("2.209×").doesNotContain("9 documented limitations");
        assertThat(a.text()).contains("[R1.m.latencyP99Ms]");
        assertThat(a.citations()).contains("R1.m.latencyP99Ms", "R1.m.latencyP50Ms");
    }

    @Test
    void citationsOfEvidenceThatDoesNotExistAreDropped() {
        List<Finding> f = InvestigatorService.cited(List.of(new Finding("CAVEAT", "x", "t", List.of("R1.m.latencyP99Ms", "R1.env", "R1.m.latencyP99Ms"))), BUNDLE);
        assertThat(f.getFirst().citations()).containsExactly("R1.m.latencyP99Ms");
    }

    @Test
    void numbersInEvidenceUseAtMostFourSignificantDigits() {
        assertThat(EvidenceBuilder.n(1997.4321)).isEqualTo("1997");
        assertThat(EvidenceBuilder.n(0.000123456)).isEqualTo("0.0001235");
        assertThat(EvidenceBuilder.n(42.0)).isEqualTo("42");
        assertThat(EvidenceBuilder.n(Double.NaN)).isEqualTo("n/a");
    }

    // ------------------------------------------------------------------ local provider contract

    @Test
    void theLocalProviderSpeaksOpenAiCompatibleChatCompletions() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<JsonNode> body = new AtomicReference<>();
        HttpServer stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        stub.createContext("/", ex -> {
            path.set(ex.getRequestURI().getPath());
            body.set(json.readTree(ex.getRequestBody().readAllBytes()));
            byte[] out = """
                    {"choices":[{"message":{"role":"assistant","content":"The pool is the limit [R1.m.latencyP99Ms]."},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":321,"completion_tokens":12}}""".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        stub.start();
        try {
            LocalModelProvider p = new LocalModelProvider("http://127.0.0.1:" + stub.getAddress().getPort() + "/v1/", "qwen2.5", 2000, 30, json);
            ModelProvider.Answer a = p.ask("system text", "user text");
            assertThat(path.get()).isEqualTo("/v1/chat/completions");
            assertThat(body.get().path("model").asString()).isEqualTo("qwen2.5");
            assertThat(body.get().path("messages").path(0).path("role").asString()).isEqualTo("system");
            assertThat(body.get().path("messages").path(1).path("content").asString()).isEqualTo("user text");
            assertThat(a.text()).contains("[R1.m.latencyP99Ms]");
            assertThat(a.stopReason()).isEqualTo("stop");
            assertThat(a.inputTokens()).isEqualTo(321L);
        } finally {
            stub.stop(0);
        }
    }

    @Test
    void aLocalProviderMustBeOnLoopback() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new dev.forge.server.config.AiProperties("local", null, null, "http://10.0.0.5:11434/v1", null, 0, 0))
                .hasMessageContaining("loopback");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new dev.forge.server.config.AiProperties("anthropic", null, " ", null, null, 0, 0))
                .hasMessageContaining("API key");
        var ok = new dev.forge.server.config.AiProperties("anthropic", null, "k", null, null, 0, 0);
        assertThat(ok.model()).isEqualTo("claude-opus-5-5");
        assertThat(ok.sendsEvidenceOffHost()).isTrue();
    }
}
