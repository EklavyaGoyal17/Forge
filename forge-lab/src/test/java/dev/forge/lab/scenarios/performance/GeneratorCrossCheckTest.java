package dev.forge.lab.scenarios.performance;

import dev.forge.lab.scenario.ParameterValidator;
import dev.forge.lab.scenario.RunContext;
import dev.forge.lab.scenario.RunSpec;
import dev.forge.lab.scenario.Scenario;
import dev.forge.lab.scenario.ScenarioCatalog;
import dev.forge.lab.scenario.ScenarioResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneratorCrossCheckTest {

    @TempDir
    Path work;

    private static final GeneratorCrossCheckScenario.Plan PLAN = new GeneratorCrossCheckScenario.Plan(TargetService.Design.ASYNC, 300,
            1_000_000_000L, 3_000_000_000L, 4, 20_000_000L, 200, 64, 2_000_000_000L);

    @Test
    void theK6ScriptIsAConstantArrivalRateLoadAgainstTheGivenLoopbackUrlOnly() {
        String js = GeneratorCrossCheckScenario.k6Script(PLAN, "http://127.0.0.1:41234/work");
        assertThat(js).contains("executor: 'constant-arrival-rate', rate: 300, timeUnit: '1s', preAllocatedVUs: 64, maxVUs: 64")
                .contains("warmup: {").contains("duration: '1000ms'")
                .contains("measure: {").contains("duration: '3000ms', startTime: '1000ms'")
                .contains("const url = 'http://127.0.0.1:41234/work';")
                .contains("timeout: '2000ms'")
                .contains("'x-forge-measured': measured ? '1' : '0'")
                .contains("handleSummary");
        assertThat(js.split("http://", -1)).hasSize(2);   // exactly one URL in the script
        String noWarmup = GeneratorCrossCheckScenario.k6Script(new GeneratorCrossCheckScenario.Plan(TargetService.Design.ASYNC, 50,
                0, 1_000_000_000L, 8, 0, 0, 8, 500_000_000L), "http://127.0.0.1:1/work");
        assertThat(noWarmup).doesNotContain("warmup:").contains("startTime: '0ms'");
    }

    @Test
    void theMeasuredPhaseIsReadFromTheK6Summary() throws Exception {
        String summary = """
                {"metrics": {
                  "http_req_duration{scenario:measure}": {"type": "trend", "contains": "time",
                     "values": {"avg": 21.9, "min": 20.4, "med": 21.7, "p(90)": 22.6, "p(95)": 23.0, "p(99)": 24.5, "max": 31.2}},
                  "http_reqs{scenario:measure}": {"type": "counter", "values": {"count": 891, "rate": 297}},
                  "http_req_failed{scenario:measure}": {"type": "rate", "values": {"rate": 0.0101, "passes": 9, "fails": 882}},
                  "dropped_iterations{scenario:measure}": {"type": "counter", "values": {"count": 9, "rate": 3}}
                }}""";
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Double> m = GeneratorCrossCheckScenario.parseK6Summary(summary, PLAN, details);
        assertThat(m).containsEntry("p50Ms", 21.7).containsEntry("p99Ms", 24.5).containsEntry("p99FromSendMs", 24.5).containsEntry("maxMs", 31.2);
        assertThat(m.get("droppedPercent")).isEqualTo(1.0);          // 9 of 300 × 3 s scheduled
        assertThat(m.get("errorPercent")).isEqualTo(1.01);
        assertThat(m.get("achievedRps")).isEqualTo(Math.round(891 * (1 - 0.0101) / 3.0 * 10) / 10.0);
        assertThat(details).containsKey("k6");
    }

    private ScenarioResult run(String variant, Map<String, Object> params) throws Exception {
        Scenario s = ScenarioCatalog.find(GeneratorCrossCheckScenario.ID).orElseThrow();
        Map<String, Object> p = new HashMap<>(Map.of("rate", 50, "warmupMs", 200, "durationMs", 1_000, "downstreamMs", 5, "cpuWorkUs", 50, "connections", 8));
        p.putAll(params);
        ParameterValidator.Result v = ParameterValidator.validate(s.descriptor(), variant, p, 1, 0);
        assertThat(v.errors()).isEmpty();
        return s.run(new RunSpec("test", s.descriptor().id(), s.descriptor().version(), variant, v.params(), 1, v.trials(), v.warmupTrials()), RunContext.noop(work));
    }

    @Test
    void theForgeGeneratorReportsBothLatencyDefinitionsAndTheServersOwnServiceTime() throws Exception {
        ScenarioResult r = run("forge", Map.of());
        assertThat(r.trials().getFirst().metrics()).containsKeys("p50Ms", "p99Ms", "p99FromSendMs", "achievedRps", "droppedPercent", "serviceP99Ms", "serverRequests");
        assertThat(r.trials().getFirst().metrics().get("droppedPercent")).isZero();
    }

    @Test
    void k6EndToEndWhereTheBinaryIsInstalled() throws Exception {
        Assumptions.assumeTrue(GeneratorCrossCheckScenario.k6Binary().isPresent(), "k6 is not installed here (it is in the FORGE Docker image)");
        ScenarioResult r = run("k6", Map.of("warmupMs", 0));
        assertThat(r.trials().getFirst().metrics()).containsKeys("p99Ms", "achievedRps", "serviceP99Ms");
        assertThat(r.trials().getFirst().metrics().get("serverRequests")).isPositive();
    }

    @Test
    void k6RefusesAWarmupShorterThanItsMinimumStage() {
        assertThatThrownBy(() -> run("k6", Map.of("warmupMs", 200))).hasMessageContaining("at least 1 s");
    }

    @Test
    void withoutK6TheVariantFailsWithAnExplanationInsteadOfNumbers() {
        Assumptions.assumeTrue(GeneratorCrossCheckScenario.k6Binary().isEmpty());
        assertThatThrownBy(() -> run("k6", Map.of("warmupMs", 0))).hasMessageContaining("k6 is not installed in this runner");
    }
}
