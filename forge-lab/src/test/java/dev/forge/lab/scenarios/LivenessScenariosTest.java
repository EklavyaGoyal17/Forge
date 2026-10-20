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
 * Livelock and starvation. Verdicts are asserted from explorer mode only (deterministic, complete
 * state graphs). Stress mode is asserted only for safety and basic progress of correct variants;
 * whether a broken variant starves within a trial is timing-dependent.
 */
class LivenessScenariosTest {

    @TempDir
    Path work;

    private ScenarioResult run(String scenario, String variant, Map<String, Object> params, int trials) throws Exception {
        Scenario s = ScenarioCatalog.find(scenario).orElseThrow();
        ParameterValidator.Result v = ParameterValidator.validate(s.descriptor(), variant, params, trials, 0);
        assertThat(v.errors()).isEmpty();
        RunSpec spec = new RunSpec("test", scenario, s.descriptor().version(), variant, v.params(), 42, v.trials(), v.warmupTrials());
        return s.run(spec, RunContext.noop(work));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> liveness(ScenarioResult r) {
        return (Map<String, Object>) r.details().get("liveness");
    }

    @Test
    void politeFlagsKeepMutualExclusionButCanLivelock() throws Exception {
        ScenarioResult r = run("concurrency.livelock", "polite-flags", Map.of("mode", "explore"), 1);
        Map<String, Object> l = liveness(r);
        assertThat(l.get("complete")).isEqualTo(true);
        assertThat(l.get("safetyViolations")).isEqualTo(0);
        assertThat(l.get("livelock")).isNotNull();
        assertThat(r.invariant().held()).isFalse();
        @SuppressWarnings("unchecked")
        Map<String, Object> cycle = (Map<String, Object>) l.get("livelock");
        assertThat((Integer) cycle.get("cycleLength")).isPositive();
    }

    @Test
    void petersonIsLivelockFreeAndStarvationFreeOnTheCompleteGraph() throws Exception {
        ScenarioResult r = run("concurrency.livelock", "peterson", Map.of("mode", "explore"), 1);
        Map<String, Object> l = liveness(r);
        assertThat(l.get("error")).isNull();
        assertThat(l.get("complete")).isEqualTo(true);
        assertThat(l.get("safetyViolations")).isEqualTo(0);
        assertThat(l.get("livelock")).isNull();
        assertThat((Map<?, ?>) l.get("starvation")).isEmpty();
        assertThat(r.invariant().held()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"peterson", "random-backoff"})
    void correctCorridorProtocolsKeepMutualExclusionUnderStress(String variant) throws Exception {
        ScenarioResult r = run("concurrency.livelock", variant, Map.of("mode", "stress", "durationMs", 100), 2);
        assertThat(r.trials()).allSatisfy(t -> {
            assertThat(t.metrics().get("maxInside")).isEqualTo(1.0);
            assertThat(t.metrics().get("minShare")).isPositive();
        });
    }

    @Test
    void readersPreferenceCanStarveTheWriter() throws Exception {
        Map<String, Object> l = liveness(run("concurrency.starvation", "readers-preference", Map.of("mode", "explore"), 1));
        assertThat(l.get("complete")).isEqualTo(true);
        assertThat(l.get("safetyViolations")).isEqualTo(0);
        assertThat((Map<String, Object>) l.get("starvation")).containsKey("writer");
        assertThat(l.get("livelock")).isNull();
    }

    @Test
    void writerPreferenceCanStarveTheReadersInstead() throws Exception {
        Map<String, Object> l = liveness(run("concurrency.starvation", "writer-preference", Map.of("mode", "explore"), 1));
        assertThat(l.get("complete")).isEqualTo(true);
        assertThat(l.get("safetyViolations")).isEqualTo(0);
        assertThat((Map<String, Object>) l.get("starvation")).containsKeys("reader-1", "reader-2").doesNotContainKey("writer");
    }

    @Test
    void ticketLockStarvesNobody() throws Exception {
        ScenarioResult r = run("concurrency.starvation", "ticket", Map.of("mode", "explore"), 1);
        Map<String, Object> l = liveness(r);
        assertThat(l.get("error")).isNull();
        assertThat(l.get("complete")).isEqualTo(true);
        assertThat(l.get("safetyViolations")).isEqualTo(0);
        assertThat((Map<?, ?>) l.get("starvation")).isEmpty();
        assertThat(l.get("livelock")).isNull();
        assertThat(r.invariant().held()).isTrue();
    }

    @Test
    void aCasLoopTicketIsLockFreeButCanStarveAThread() throws Exception {
        Map<String, Object> l = liveness(run("concurrency.starvation", "cas-ticket", Map.of("mode", "explore"), 1));
        assertThat(l.get("error")).isNull();
        assertThat(l.get("complete")).isEqualTo(true);
        assertThat(l.get("safetyViolations")).isEqualTo(0);
        assertThat(l.get("livelock")).isNull();   // the system as a whole always progresses...
        assertThat((Map<String, Object>) l.get("starvation")).isNotEmpty();   // ...but not every thread
    }

    @ParameterizedTest
    @ValueSource(strings = {"ticket", "cas-ticket", "jdk-fair", "jdk-nonfair", "readers-preference"})
    void readerWriterExclusionHoldsUnderStress(String variant) throws Exception {
        ScenarioResult r = run("concurrency.starvation", variant,
                Map.of("mode", "stress", "readers", 2, "durationMs", 200, "writeIntervalMs", 2), 2);
        assertThat(r.trials()).allSatisfy(t -> assertThat(t.note() == null || !t.note().contains("inside")).as(String.valueOf(t.note())).isTrue());
        if (!variant.equals("readers-preference")) {
            assertThat(r.trials()).allSatisfy(t -> assertThat(t.metrics().get("writesCompleted")).isPositive());
        }
    }

    @Test
    void ticketLockSerialisesReaders() throws Exception {
        ScenarioResult r = run("concurrency.starvation", "ticket", Map.of("mode", "stress", "readers", 3, "durationMs", 200), 1);
        assertThat(r.trials().getFirst().metrics().get("maxConcurrentReaders")).isEqualTo(1.0);
    }
}
