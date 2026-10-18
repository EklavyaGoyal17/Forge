package dev.forge.lab.scenarios;

import dev.forge.lab.scenario.ParameterValidator;
import dev.forge.lab.scenario.RunContext;
import dev.forge.lab.scenario.RunSpec;
import dev.forge.lab.scenario.Scenario;
import dev.forge.lab.scenario.ScenarioCatalog;
import dev.forge.lab.scenario.ScenarioResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correct variants must hold their invariant (a deterministic property of correct code).
 * Broken variants are asserted only through explorer mode, which is deterministic; their stress
 * behaviour is timing-dependent and is not asserted, to keep the suite free of flaky tests.
 */
class ConcurrencyScenariosTest {

    @TempDir
    Path work;

    private ScenarioResult run(String scenario, String variant, Map<String, Object> params, int trials) throws Exception {
        Scenario s = ScenarioCatalog.find(scenario).orElseThrow();
        ParameterValidator.Result v = ParameterValidator.validate(s.descriptor(), variant, params, trials, 0);
        assertThat(v.errors()).isEmpty();
        RunSpec spec = new RunSpec("test", scenario, s.descriptor().version(), variant, v.params(), 42, v.trials(), v.warmupTrials());
        return s.run(spec, RunContext.noop(work));
    }

    @ParameterizedTest
    @ValueSource(strings = {"synchronized", "reentrant-lock", "atomic-cas", "long-adder"})
    void correctLostUpdateVariantsHoldUnderStress(String variant) throws Exception {
        ScenarioResult r = run("concurrency.lost-update", variant,
                Map.of("mode", "stress", "threads", 4, "depositsPerThread", 20_000), 5);
        assertThat(r.trials()).hasSize(5);
        assertThat(r.invariant().held()).isTrue();
        assertThat(r.trials()).allSatisfy(t -> assertThat(t.metrics().get("lostUpdates")).isZero());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unsynchronized", "volatile"})
    void brokenLostUpdateVariantsHaveViolatingSchedulesInExplorer(String variant) throws Exception {
        ScenarioResult r = run("concurrency.lost-update", variant,
                Map.of("mode", "explore", "exploreThreads", 2, "exploreDeposits", 1), 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> explorer = (Map<String, Object>) r.details().get("explorer");
        assertThat(explorer.get("exhaustive")).isEqualTo(true);
        assertThat(explorer.get("schedulesExplored")).isEqualTo(6);
        assertThat(explorer.get("violations")).isEqualTo(4);
        assertThat(explorer.get("counterexample")).isNotNull();
        assertThat(r.invariant().held()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"synchronized", "reentrant-lock", "atomic-cas", "long-adder"})
    void correctLostUpdateVariantsHaveNoViolatingScheduleInExplorer(String variant) throws Exception {
        ScenarioResult r = run("concurrency.lost-update", variant,
                Map.of("mode", "explore", "exploreThreads", 2, "exploreDeposits", 2), 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> explorer = (Map<String, Object>) r.details().get("explorer");
        assertThat(explorer.get("exhaustive")).isEqualTo(true);
        assertThat(explorer.get("violations")).isEqualTo(0);
        assertThat(explorer.get("errors")).isEqualTo(0);
        assertThat(r.invariant().held()).isTrue();
    }

    @Test
    void nestedLocksDeadlockInExplorerAndOrderedLocksDoNot() throws Exception {
        ScenarioResult nested = run("concurrency.deadlock", "nested-locks", Map.of("mode", "explore"), 1);
        ScenarioResult ordered = run("concurrency.deadlock", "ordered-locks", Map.of("mode", "explore"), 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> n = (Map<String, Object>) nested.details().get("explorer");
        @SuppressWarnings("unchecked")
        Map<String, Object> o = (Map<String, Object>) ordered.details().get("explorer");
        assertThat((Integer) n.get("deadlocks")).isPositive();
        assertThat(o.get("deadlocks")).isEqualTo(0);
        assertThat(o.get("violations")).isEqualTo(0);
        assertThat(o.get("exhaustive")).isEqualTo(true);
    }

    @Test
    void tryLockNeverDeadlocksInExplorer() throws Exception {
        ScenarioResult r = run("concurrency.deadlock", "trylock-backoff",
                Map.of("mode", "explore", "maxSchedules", 500, "maxSteps", 60), 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> e = (Map<String, Object>) r.details().get("explorer");
        assertThat(e.get("deadlocks")).isEqualTo(0);
        assertThat(e.get("errors")).isEqualTo(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ordered-locks", "trylock-backoff"})
    void deadlockFreeVariantsCompleteUnderStress(String variant) throws Exception {
        ScenarioResult r = run("concurrency.deadlock", variant,
                Map.of("mode", "stress", "accounts", 3, "threads", 4, "transfersPerThread", 5_000), 3);
        assertThat(r.invariant().held()).isTrue();
        assertThat(r.trials()).allSatisfy(t -> {
            assertThat(t.metrics().get("deadlocked")).isZero();
            assertThat(t.metrics().get("conserved")).isEqualTo(1.0);
            assertThat(t.metrics().get("completedTransfers")).isEqualTo(20_000.0);
        });
    }

    @Test
    void checkThenActBufferOverfillsInExplorerAndLockedBufferDoesNot() throws Exception {
        Map<String, Object> p = Map.of("mode", "explore", "exploreProducers", 2, "exploreOffers", 1, "exploreCapacity", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> racy = (Map<String, Object>) run("concurrency.backpressure", "check-then-act", p, 1).details().get("explorer");
        @SuppressWarnings("unchecked")
        Map<String, Object> locked = (Map<String, Object>) run("concurrency.backpressure", "locked-check-then-act", p, 1).details().get("explorer");
        assertThat(racy.get("exhaustive")).isEqualTo(true);
        assertThat((Integer) racy.get("violations")).isPositive();
        assertThat(racy.get("counterexample").toString()).contains("buffered 2 of capacity 1");
        assertThat(locked.get("exhaustive")).isEqualTo(true);
        assertThat(locked.get("violations")).isEqualTo(0);
        assertThat(locked.get("errors")).isEqualTo(0);
    }

    /** 4,000 items/s offered against ~1,000 items/s of service: a 4× overload. */
    private static final Map<String, Object> OVERLOAD = Map.of("mode", "stress", "producers", 2, "ratePerProducer", 2_000,
            "consumers", 1, "serviceMicros", 1_000, "capacity", 50, "durationMs", 300);

    @ParameterizedTest
    @ValueSource(strings = {"blocking", "drop-newest", "locked-check-then-act"})
    void boundedBuffersStayBoundedAndLoseNothingUnderOverload(String variant) throws Exception {
        ScenarioResult r = run("concurrency.backpressure", variant, OVERLOAD, 2);
        assertThat(r.invariant().held()).as(r.observations().toString()).isTrue();
        assertThat(r.trials()).allSatisfy(t -> assertThat(t.metrics().get("maxQueueDepth")).isLessThanOrEqualTo(50.0));
    }

    @Test
    void backpressureMovesWaitingToTheProducerWhileSheddingDropsWork() throws Exception {
        ScenarioResult blocking = run("concurrency.backpressure", "blocking", OVERLOAD, 1);
        ScenarioResult shedding = run("concurrency.backpressure", "drop-newest", OVERLOAD, 1);
        assertThat(blocking.trials().getFirst().metrics().get("droppedPercent")).isZero();
        assertThat(shedding.trials().getFirst().metrics().get("droppedPercent")).isPositive();
    }

    @Test
    void uninstrumentedVariantsAreRejectedInExplorerMode() {
        var d = ScenarioCatalog.find("concurrency.backpressure").orElseThrow().descriptor();
        var v = ParameterValidator.validate(d, "unbounded", Map.of("mode", "explore"), 1, 0);
        assertThat(v.errors()).extracting(ParameterValidator.FieldError::field).contains("variant");
        assertThat(ParameterValidator.validate(d, "check-then-act", Map.of("mode", "explore"), 1, 0).errors()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"volatile", "atomic"})
    void volatileStopFlagIsObserved(String variant) throws Exception {
        ScenarioResult r = run("concurrency.visibility", variant,
                Map.of("spinBeforeStopMs", 100, "observeWindowMs", 2_000), 2);
        assertThat(r.invariant().held()).isTrue();
    }
}
