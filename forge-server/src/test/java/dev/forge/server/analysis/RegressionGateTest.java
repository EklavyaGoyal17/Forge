package dev.forge.server.analysis;

import dev.forge.lab.scenario.MetricSpec.Direction;
import dev.forge.lab.stats.Comparison;
import dev.forge.server.analysis.RegressionService.Gate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The gate rules, on comparisons built from fixed samples (no database, no runner). */
class RegressionGateTest {

    private static final double[] FAST = {10.0, 10.1, 9.9, 10.0, 10.05, 9.95};
    private static final double[] SLOW = {20.0, 20.2, 19.8, 20.1, 19.9, 20.0};
    private static final double[] FAST_TOO = {10.02, 9.97, 10.08, 9.93, 10.01, 10.03};

    private static ComparisonService.MetricComparison latency(double[] baseline, double[] candidate, boolean primary) {
        Comparison.Result r = Comparison.compare("latencyP99Ms", Direction.LOWER_IS_BETTER, baseline, candidate, 7);
        return new ComparisonService.MetricComparison(primary ? "Client p99 latency" : "Server p99", "ms", primary, r);
    }

    private static ComparisonService.ComparisonView view(List<ComparisonService.MetricComparison> metrics,
                                                         List<ComparisonService.Difference> params,
                                                         List<ComparisonService.Difference> env,
                                                         List<String> warnings,
                                                         Boolean baselineHeld, Boolean candidateHeld) {
        return new ComparisonService.ComparisonView(UUID.randomUUID(), UUID.randomUUID(), "s", "a", "b", "latencyP99Ms",
                metrics, params, env, warnings, null, null, baselineHeld, candidateHeld);
    }

    private static RegressionService.Check gate(ComparisonService.ComparisonView v) {
        return RegressionService.gate(UUID.randomUUID(), 2, "b", v);
    }

    @Test
    void worsePrimaryMetricWithIntervalExcludingOneIsARegression() {
        var c = gate(view(List.of(latency(FAST, SLOW, true)), List.of(), List.of(), List.of(), null, null));
        assertThat(c.gate()).isEqualTo(Gate.REGRESSION);
        assertThat(c.primary().ciLow()).isGreaterThan(1.0);
    }

    @Test
    void betterPrimaryMetricIsAnImprovement() {
        assertThat(gate(view(List.of(latency(SLOW, FAST, true)), List.of(), List.of(), List.of(), null, null)).gate())
                .isEqualTo(Gate.IMPROVEMENT);
    }

    @Test
    void overlappingSamplesAreNoDifferenceDetected() {
        assertThat(gate(view(List.of(latency(FAST, FAST_TOO, true)), List.of(), List.of(), List.of(), null, null)).gate())
                .isEqualTo(Gate.NO_DIFFERENCE_DETECTED);
    }

    @Test
    void differentParametersOrEnvironmentMeanNoVerdict() {
        var params = List.of(new ComparisonService.Difference("params.rate", "rate", "300", "600"));
        var env = List.of(new ComparisonService.Difference("/runner/javaVersion", "Java version", "21", "25"));
        var p = gate(view(List.of(latency(FAST, SLOW, true)), params, List.of(), List.of(), null, null));
        var e = gate(view(List.of(latency(FAST, SLOW, true)), List.of(), env, List.of(), null, null));
        assertThat(p.gate()).isEqualTo(Gate.NOT_COMPARABLE);
        assertThat(p.reasons()).anySatisfy(r -> assertThat(r).contains("rate"));
        assertThat(e.gate()).isEqualTo(Gate.NOT_COMPARABLE);
        assertThat(e.reasons()).anySatisfy(r -> assertThat(r).contains("Java version"));
    }

    @Test
    void differentScenarioVersionsMeanNoVerdict() {
        var v = view(List.of(latency(FAST, SLOW, true)), List.of(), List.of(),
                List.of("Different scenario versions (v1 vs v2): the measured code itself may have changed."), null, null);
        assertThat(gate(v).gate()).isEqualTo(Gate.NOT_COMPARABLE);
    }

    @Test
    void aNewlyViolatedInvariantIsARegressionEvenIfTheMetricImproved() {
        var c = gate(view(List.of(latency(SLOW, FAST, true)), List.of(), List.of(), List.of(), true, false));
        assertThat(c.gate()).isEqualTo(Gate.REGRESSION);
        assertThat(c.reasons()).first().asString().startsWith("Correctness");
    }

    @Test
    void withoutAPrimaryMetricThereIsNoVerdict() {
        assertThat(gate(view(List.of(), List.of(), List.of(), List.of(), null, null)).gate()).isEqualTo(Gate.NOT_COMPARABLE);
    }

    @Test
    void secondaryRegressionsAreListedButDoNotGate() {
        var c = gate(view(List.of(latency(FAST, FAST_TOO, true), latency(FAST, SLOW, false)), List.of(), List.of(), List.of(), null, null));
        assertThat(c.gate()).isEqualTo(Gate.NO_DIFFERENCE_DETECTED);
        assertThat(c.otherRegressedMetrics()).containsExactly("Server p99");
    }
}
