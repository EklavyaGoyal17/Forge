package dev.forge.lab.scenarios;

import dev.forge.lab.scenario.ParameterValidator;
import dev.forge.lab.scenario.RunContext;
import dev.forge.lab.scenario.RunSpec;
import dev.forge.lab.scenario.Scenario;
import dev.forge.lab.scenario.ScenarioCatalog;
import dev.forge.lab.scenario.ScenarioResult;
import dev.forge.lab.scenarios.performance.ThreadPoolStarvationScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real, short runs of the HTTP scenario. Only structure and accounting are asserted — never
 * latency or throughput values, which depend on the machine.
 */
class PerformanceScenarioTest {

    @TempDir
    Path work;

    private static final Map<String, Object> SMALL = Map.of(
            "rate", 50, "warmupMs", 200, "durationMs", 500, "poolSize", 2, "downstreamMs", 5,
            "cpuWorkUs", 50, "connections", 8, "requestTimeoutMs", 1_000);

    private ScenarioResult run(String variant, RunContext ctx) throws Exception {
        Scenario s = ScenarioCatalog.find(ThreadPoolStarvationScenario.ID).orElseThrow();
        ParameterValidator.Result v = ParameterValidator.validate(s.descriptor(), variant, SMALL, 2, 0);
        assertThat(v.errors()).isEmpty();
        RunSpec spec = new RunSpec("test", s.descriptor().id(), s.descriptor().version(), variant, v.params(), 1, v.trials(), v.warmupTrials());
        return s.run(spec, ctx);
    }

    @ParameterizedTest
    @CsvSource({"blocking-pool, 2", "large-pool, 16", "async, 2", "virtual-threads, 0"})
    @SuppressWarnings("unchecked")
    void everyDesignProducesConsistentPerTrialEvidence(String variant, int expectedWorkers) throws Exception {
        ScenarioResult r = run(variant, RunContext.noop(work));

        assertThat(r.trials()).hasSize(2);
        assertThat(r.invariant().held()).isNull();
        assertThat(r.observations()).isNotEmpty();
        for (ScenarioResult.Trial t : r.trials()) {
            assertThat(t.metrics()).containsKeys("achievedRps", "offeredRps", "errorPercent", "timeouts", "timerLagP99Ms");
            assertThat(t.metrics().get("offeredRps")).isEqualTo(50.0);
            if (t.metrics().containsKey("latencyP99Ms")) {
                assertThat(t.metrics().get("latencyP50Ms")).isLessThanOrEqualTo(t.metrics().get("latencyP99Ms"));
                assertThat(t.metrics().get("latencyP99Ms")).isLessThanOrEqualTo(t.metrics().get("latencyMaxMs"));
            }
            assertThat(t.metrics().containsKey("littleBoundRps")).isEqualTo(expectedWorkers > 0 && t.metrics().containsKey("serviceP50Ms"));
        }

        Map<String, Object> d = (Map<String, Object>) r.details().get("httpLoad");
        assertThat(d.get("bindAddress")).isEqualTo("127.0.0.1");
        assertThat(d.get("workers")).isEqualTo(expectedWorkers);
        assertThat(d.get("design")).isEqualTo(variant);
        List<Map<String, Object>> trials = (List<Map<String, Object>>) d.get("trials");
        assertThat(trials).hasSize(2);
        for (Map<String, Object> t : trials) {
            // 50 req/s × (0.2 s warm-up + 0.5 s sustained); only the sustained 25 are measured
            assertThat(t.get("scheduled")).isEqualTo(35L);
            assertThat(t.get("offered")).isEqualTo(25L);
            long accounted = (Long) t.get("ok") + (Long) t.get("httpErrors") + (Long) t.get("failures") + (Long) t.get("timeouts");
            assertThat(accounted + (Long) t.get("unfinished")).isEqualTo(25L);
            Map<String, Object> client = (Map<String, Object>) t.get("client");
            assertThat(client.get("count")).isEqualTo(accounted);
            assertThat((List<?>) t.get("series")).isNotEmpty();
            assertThat((Double) t.get("measureStartMs")).isEqualTo(200.0);
            assertThat((Double) t.get("measureEndMs")).isEqualTo(700.0);
        }
        Map<String, Object> aggregate = (Map<String, Object>) d.get("aggregate");
        assertThat(aggregate).containsKeys("client", "uncorrected", "service", "queueWait", "spectrum");
    }

    @Test
    void refusesToStartWhenTheConfiguredTrialsCannotFinishBeforeTheDeadline() {
        long deadline = System.nanoTime() + 1_000_000_000L;
        RunContext tight = new RunContext() {
            @Override public void event(String level, String message) { }
            @Override public void progress(long done, long total, String phase) { }
            @Override public void artifact(String name, String contentType, String content) { }
            @Override public Path workDir() { return work; }
            @Override public long deadlineNanos() { return deadline; }
        };
        assertThatThrownBy(() -> run("blocking-pool", tight))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not fit");
    }

    @Test
    void parameterBoundsKeepTheLoadSmall() {
        var d = ScenarioCatalog.find(ThreadPoolStarvationScenario.ID).orElseThrow().descriptor();
        assertThat(d.parameter("rate").orElseThrow().max()).isLessThanOrEqualTo(2_000);
        assertThat(d.parameter("connections").orElseThrow().max()).isLessThanOrEqualTo(256);
        assertThat(d.parameter("durationMs").orElseThrow().max()).isLessThanOrEqualTo(10_000);
        var r = ParameterValidator.validate(d, "async", Map.of("rate", 100_000, "connections", 10_000), 50, 10);
        assertThat(r.errors()).extracting(ParameterValidator.FieldError::field)
                .contains("params.rate", "params.connections", "trials", "warmupTrials");
    }
}
