package dev.forge.lab.scenarios.failure;

import dev.forge.lab.scenario.ParameterValidator;
import dev.forge.lab.scenario.RunContext;
import dev.forge.lab.scenario.RunSpec;
import dev.forge.lab.scenario.Scenario;
import dev.forge.lab.scenario.ScenarioCatalog;
import dev.forge.lab.scenario.ScenarioResult;
import dev.forge.lab.scenarios.failure.CircuitBreaker.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic parts are asserted exactly (breaker state machine on a fake clock, fault behaviour
 * of the dependency, request accounting). Recovery times and latencies are timing-dependent and are
 * only checked for presence and structure.
 */
class FailureLabTest {

    @TempDir
    Path work;

    // ------------------------------------------------------------------ circuit breaker

    @Test
    void breakerOpensAtTheFailureRateFailsFastThenProbesAndCloses() {
        AtomicLong now = new AtomicLong();
        CircuitBreaker b = new CircuitBreaker(4, 4, 0.5, 1_000, 2, now::get);
        for (boolean ok : new boolean[] {true, true, false}) {
            assertThat(b.tryAcquire()).isTrue();
            b.onResult(ok);
        }
        assertThat(b.state()).isEqualTo(State.CLOSED);        // 1 of 3: below minCalls anyway
        assertThat(b.tryAcquire()).isTrue();
        b.onResult(false);                                     // 2 of 4 = 50% → open
        assertThat(b.state()).isEqualTo(State.OPEN);
        now.set(999);
        assertThat(b.tryAcquire()).isFalse();                  // still cooling down
        now.set(1_000);
        assertThat(b.tryAcquire()).isTrue();                   // half-open: first probe
        assertThat(b.tryAcquire()).isTrue();                   // second probe
        assertThat(b.tryAcquire()).isFalse();                  // no more than 2 probes at once
        b.onResult(true);
        assertThat(b.state()).isEqualTo(State.HALF_OPEN);
        b.onResult(true);
        assertThat(b.state()).isEqualTo(State.CLOSED);
        assertThat(b.transitions()).extracting(CircuitBreaker.Transition::to)
                .containsExactly(State.OPEN, State.HALF_OPEN, State.CLOSED);
    }

    @Test
    void aFailedProbeReopensTheBreakerAndOldFailuresSlideOutOfTheWindow() {
        AtomicLong now = new AtomicLong();
        CircuitBreaker b = new CircuitBreaker(4, 4, 0.75, 100, 1, now::get);
        for (boolean ok : new boolean[] {false, false, true, true, true, true}) {   // window keeps the last 4
            b.tryAcquire();
            b.onResult(ok);
        }
        assertThat(b.state()).isEqualTo(State.CLOSED);
        for (int i = 0; i < 3; i++) {
            b.tryAcquire();
            b.onResult(false);                                 // last 4: T F F F = 75% → open
        }
        assertThat(b.state()).isEqualTo(State.OPEN);
        now.set(100);
        assertThat(b.tryAcquire()).isTrue();
        b.onResult(false);
        assertThat(b.state()).isEqualTo(State.OPEN);
        now.set(150);
        assertThat(b.tryAcquire()).isFalse();                  // the cool-down restarted at the failed probe
    }

    // ------------------------------------------------------------------ inventory service faults

    private static HttpResponse<String> get(HttpClient http, URI uri) throws Exception {
        return http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theDependencyInjectsEachFaultAndRestartsOnANewPort() throws Exception {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).proxy(HttpClient.Builder.NO_PROXY).build();
        try (InventoryService s = new InventoryService(2, 0, 300_000_000L, 1.0, 7)) {
            assertThat(s.address().getHost()).isEqualTo("127.0.0.1");
            assertThat(get(http, s.address()).statusCode()).isEqualTo(200);

            s.inject(InventoryService.Fault.LATENCY);
            long t = System.nanoTime();
            assertThat(get(http, s.address()).statusCode()).isEqualTo(200);
            assertThat(System.nanoTime() - t).isGreaterThanOrEqualTo(300_000_000L);
            s.clear();

            s.inject(InventoryService.Fault.ERRORS);              // error fraction 1.0
            assertThat(get(http, s.address()).statusCode()).isEqualTo(500);
            s.clear();

            s.inject(InventoryService.Fault.DROP);
            assertThatThrownBy(() -> get(http, s.address())).isInstanceOf(IOException.class);
            s.clear();

            URI before = s.address();
            s.inject(InventoryService.Fault.REFUSE);
            assertThatThrownBy(() -> get(http, before)).isInstanceOf(ConnectException.class);
            s.clear();
            assertThat(s.address()).isNotEqualTo(before);
            assertThat(get(http, s.address()).statusCode()).isEqualTo(200);

            InventoryService.Stats st = s.stats();
            assertThat(st.errors()).isEqualTo(1);
            // The JDK HttpClient may resend an idempotent GET once when a pooled connection closes
            // before any response, so the dependency can see the dropped request more than once.
            assertThat(st.dropped()).isBetween(1L, 2L);
            assertThat(st.restarts()).isEqualTo(1);
        }
    }

    @Test
    void errorSelectionIsAFunctionOfSeedAndSequence() {
        assertThat(InventoryService.uniform(7, 3)).isEqualTo(InventoryService.uniform(7, 3));
        assertThat(InventoryService.uniform(7, 3)).isNotEqualTo(InventoryService.uniform(8, 3));
        long below = 0;
        for (int i = 0; i < 10_000; i++) {
            below += InventoryService.uniform(42, i) < 0.3 ? 1 : 0;
        }
        assertThat(below).isBetween(2_800L, 3_200L);
    }

    // ------------------------------------------------------------------ whole scenario

    private ScenarioResult run(String variant, Map<String, Object> overrides) throws Exception {
        Map<String, Object> p = new HashMap<>(Map.of("rate", 50, "warmupMs", 200, "durationMs", 2_000,
                "faultStartMs", 300, "faultDurationMs", 700, "requestTimeoutMs", 1_000));
        p.putAll(overrides);
        Scenario s = ScenarioCatalog.find(DownstreamFaultScenario.ID).orElseThrow();
        ParameterValidator.Result v = ParameterValidator.validate(s.descriptor(), variant, p, 1, 0);
        assertThat(v.errors()).isEmpty();
        RunSpec spec = new RunSpec("test", s.descriptor().id(), s.descriptor().version(), variant, v.params(), 42, v.trials(), v.warmupTrials());
        return s.run(spec, RunContext.noop(work));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> trial(ScenarioResult r) {
        return ((List<Map<String, Object>>) ((Map<String, Object>) r.details().get("failure")).get("trials")).getFirst();
    }

    @Test
    void withoutAFaultEveryRequestSucceedsWithOneAttempt() throws Exception {
        ScenarioResult r = run("retry-immediate", Map.of("fault", "none"));
        Map<String, Double> m = r.trials().getFirst().metrics();
        assertThat(m.get("successPercent")).isEqualTo(100.0);
        assertThat(m.get("amplification")).isBetween(0.95, 1.05);
        assertThat(m.get("retriesPerRequest")).isZero();
        assertThat(m).doesNotContainKey("recoveryMs");
    }

    @Test
    void immediateRetriesMultiplyAttemptsWhenTheDependencyFails() throws Exception {
        ScenarioResult r = run("retry-immediate", Map.of("fault", "errors", "errorPercent", 100, "maxRetries", 2));
        Map<String, Object> t = trial(r);
        long attempts = ((Number) t.get("attempts")).longValue();
        long retries = ((Number) t.get("retries")).longValue();
        assertThat(retries).isPositive();
        assertThat(attempts).isGreaterThan(retries);
        assertThat(r.trials().getFirst().metrics().get("successPercent")).isLessThan(100.0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = (List<Map<String, Object>>) t.get("series");
        assertThat(series).isNotEmpty().allSatisfy(p -> assertThat(p).containsKeys("tMs", "attempts", "backlog", "breaker", "completed", "errors"));
    }

    @Test
    void theBreakerOpensUnderTotalFailureAndShortCircuitsRequests() throws Exception {
        ScenarioResult r = run("circuit-breaker", Map.of("fault", "errors", "errorPercent", 100));
        Map<String, Object> t = trial(r);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> transitions = (List<Map<String, Object>>) t.get("breakerTransitions");
        assertThat(transitions).extracting(x -> x.get("state")).contains("OPEN");
        assertThat(((Number) t.get("shortCircuited")).longValue()).isPositive();
        assertThat(r.trials().getFirst().metrics().get("breakerOpenMs")).isPositive();
    }

    @Test
    void aRetryBudgetCapsRetries() throws Exception {
        ScenarioResult r = run("retry-budget", Map.of("fault", "errors", "errorPercent", 100, "maxRetries", 3));
        Map<String, Object> t = trial(r);
        long requests = ((Number) t.get("offered")).longValue();
        long retries = ((Number) t.get("retries")).longValue();
        // 10% of requests (measured and warm-up) plus the initial allowance of 10 tokens
        assertThat(retries).isLessThanOrEqualTo((long) Math.ceil(0.1 * (requests + 10)) + 10);
        assertThat(((Number) t.get("budgetDenied")).longValue()).isPositive();
    }

    @Test
    void plansThatCouldExhaustLocalPortsOrOutliveThePhaseAreRejected() {
        assertThatThrownBy(() -> run("retry-immediate", Map.of("rate", 500, "durationMs", 30_000, "maxRetries", 5)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("attempts planned");
        assertThatThrownBy(() -> run("timeout", Map.of("faultStartMs", 1_800, "faultDurationMs", 500)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inside the sustained phase");
    }
}
