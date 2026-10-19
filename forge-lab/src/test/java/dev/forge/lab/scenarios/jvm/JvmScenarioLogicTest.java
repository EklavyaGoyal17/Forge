package dev.forge.lab.scenarios.jvm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure logic of the JVM Lab scenarios: no threads, no JFR, no timing. */
class JvmScenarioLogicTest {

    @Test
    void invocationAxisMappingFindsTheFirstInvocationThatStartedAfterAnEvent() {
        long[] starts = {100, 200, 300, 400};
        assertThat(WarmupScenario.firstStartingAtOrAfter(starts, 50)).isZero();
        assertThat(WarmupScenario.firstStartingAtOrAfter(starts, 200)).isEqualTo(1);
        assertThat(WarmupScenario.firstStartingAtOrAfter(starts, 250)).isEqualTo(2);
        assertThat(WarmupScenario.firstStartingAtOrAfter(starts, 401)).isEqualTo(-1);
    }

    @Test
    void warmupMetricsAreComputedFromTheSeries() {
        long[] series = new long[100];
        for (int i = 0; i < series.length; i++) {
            series[i] = i < 40 ? 1_000_000 : 1_000;      // 40 slow calls, then fast
        }
        Map<String, Double> m = WarmupScenario.metrics(series);
        assertThat(m.get("steadyStateUs")).isEqualTo(1.0);
        assertThat(m.get("firstInvocationUs")).isEqualTo(1_000.0);
        assertThat(m.get("totalMs")).isEqualTo((40 * 1_000_000 + 60 * 1_000) / 1e6);
        // windows of 25 starting at 0..27 still hold a majority of slow calls
        assertThat(m.get("invocationsToSteady")).isEqualTo(28.0);
        assertThat(m.get("warmupCostMs")).isEqualTo(40 * (1_000_000 - 1_000) / 1e6);
    }

    @Test
    void aLateSlowStretchDoesNotMoveTheSteadyStatePoint() {
        long[] series = new long[200];
        for (int i = 0; i < series.length; i++) {
            series[i] = i < 40 || (i >= 150 && i < 165) ? 1_000_000 : 1_000;
        }
        assertThat(WarmupScenario.metrics(series).get("invocationsToSteady")).isEqualTo(28.0);
    }

    @Test
    void aSeriesThatIsFastFromTheStartNeedsNoWarmup() {
        long[] flat = new long[50];
        java.util.Arrays.fill(flat, 2_000);
        Map<String, Double> m = WarmupScenario.metrics(flat);
        assertThat(m.get("invocationsToSteady")).isZero();
        assertThat(m.get("warmupCostMs")).isZero();
    }

    @Test
    void heavyAndLightImplementationsAgreeOnEdgeCases() {
        int[] empty = {};
        int[] mixed = {0, 1, 2, 999_999, 128, 127, 4};
        for (int[] data : List.of(empty, mixed)) {
            long ref = AllocationScenario.referenceAggregate(data);
            assertThat(AllocationScenario.boxedStream(data)).isEqualTo(ref);
            assertThat(AllocationScenario.primitiveStream(data)).isEqualTo(ref);
            assertThat(AllocationScenario.primitiveLoop(data)).isEqualTo(ref);
            String text = AllocationScenario.referenceRender(data);
            assertThat(AllocationScenario.concatInLoop(data)).isEqualTo(text);
            assertThat(AllocationScenario.stringBuilder(data)).isEqualTo(text);
            assertThat(AllocationScenario.presizedBuilder(data)).isEqualTo(text);
        }
        assertThat(AllocationScenario.referenceAggregate(mixed)).isEqualTo(0L + 4 + 128L * 128 + 16);
    }

    @Test
    void contentionWorkIsAPureFunctionSoTheTotalIsOrderIndependent() {
        assertThat(LockContentionScenario.mix(3, 17, 5)).isEqualTo(LockContentionScenario.mix(3, 17, 5));
        assertThat(LockContentionScenario.mix(3, 17, 5)).isNotEqualTo(LockContentionScenario.mix(4, 17, 5));
        assertThat(LockContentionScenario.mix(0, 0, 0)).isZero();
    }
}
