package dev.forge.lab.scenarios;

import dev.forge.lab.jvm.JfrSummary;
import dev.forge.lab.scenario.ParameterValidator;
import dev.forge.lab.scenario.RunContext;
import dev.forge.lab.scenario.RunSpec;
import dev.forge.lab.scenario.Scenario;
import dev.forge.lab.scenario.ScenarioCatalog;
import dev.forge.lab.scenario.ScenarioResult;
import dev.forge.lab.scenarios.jvm.AllocationScenario;
import dev.forge.lab.scenarios.jvm.LockContentionScenario;
import dev.forge.lab.scenarios.jvm.WarmupScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Short, real runs of the JVM Lab scenarios in this JVM. Only structure, accounting and invariants
 * are asserted — never a time, a speed-up or a pause length.
 */
class JvmScenariosTest {

    @TempDir
    Path work;

    private ScenarioResult run(String scenario, String variant, Map<String, Object> params, int trials, int warmup)
            throws Exception {
        Scenario s = ScenarioCatalog.find(scenario).orElseThrow();
        ParameterValidator.Result v = ParameterValidator.validate(s.descriptor(), variant, params, trials, warmup);
        assertThat(v.errors()).isEmpty();
        RunSpec spec = new RunSpec("test", scenario, s.descriptor().version(), variant, v.params(), 7, v.trials(), v.warmupTrials());
        return s.run(spec, RunContext.noop(work));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jvm(ScenarioResult r) {
        return (Map<String, Object>) r.details().get("jvm");
    }

    // ---- warm-up -------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void warmupTimesEveryInvocationOfEveryTrialAndChecksTheResult() throws Exception {
        ScenarioResult r = run(WarmupScenario.ID, "tiered", Map.of("invocations", 300, "workSize", 2_000), 2, 0);

        assertThat(r.trials()).hasSize(2);
        assertThat(r.invariant().held()).isTrue();
        for (ScenarioResult.Trial t : r.trials()) {
            assertThat(t.metrics()).containsKeys("totalMs", "firstInvocationUs", "steadyStateUs", "warmupCostMs",
                    "invocationsToSteady", "compilations");
            assertThat(t.metrics().get("invocationsToSteady")).isBetween(0.0, 300.0);
            assertThat(t.metrics().get("warmupCostMs")).isLessThanOrEqualTo(t.metrics().get("totalMs"));
        }
        Map<String, Object> d = jvm(r);
        assertThat(d.get("kind")).isEqualTo("warmup");
        Map<String, Object> warmup = (Map<String, Object>) d.get("warmup");
        List<Map<String, Object>> series = (List<Map<String, Object>>) warmup.get("trials");
        assertThat(series).hasSize(2);
        for (Map<String, Object> s : series) {
            long[] nanos = (long[]) s.get("invocationNanos");
            assertThat(nanos).hasSize(300);
            assertThat(java.util.Arrays.stream(nanos).min().orElseThrow()).isPositive();
            for (Map<String, Object> c : (List<Map<String, Object>>) s.get("compilations")) {
                assertThat((int) c.get("atInvocation")).isBetween(-1, 299);
                assertThat((int) c.get("level")).isBetween(1, 4);
                assertThat(c.get("method")).isIn("work", "measure");
            }
        }
        // each trial's compilations belong to that trial's freshly loaded copy of the kernel
        JfrSummary jfr = (JfrSummary) d.get("jfr");
        assertThat(jfr.compilations()).filteredOn(c -> c.method().contains("WarmupKernel"))
                .allSatisfy(c -> assertThat(c.classLoader()).matches("warmup-trial-[12]"));
        assertThat(jfr.markers()).extracting(JfrSummary.Marker::label)
                .containsExactly("warmup-trial-1 start", "warmup-trial-1 end", "warmup-trial-2 start", "warmup-trial-2 end");
    }

    @ParameterizedTest
    @CsvSource({"c1-only, -XX:TieredStopAtLevel=1", "c2-only, -XX:-TieredCompilation", "interpreter, -Xint"})
    void jitModeVariantsRefuseToRunWithoutTheirJvmFlag(String variant, String flag) {
        assumeFalse(ManagementFactory.getRuntimeMXBean().getInputArguments().contains(flag));
        assertThatThrownBy(() -> run(WarmupScenario.ID, variant, Map.of("invocations", 200, "workSize", 100), 1, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(flag);
    }

    // ---- allocation ----------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"aggregate, boxed-stream", "aggregate, primitive-stream", "aggregate, primitive-loop",
            "render, concat-in-loop", "render, string-builder", "render, presized-builder"})
    void everyAllocationVariantReturnsTheReferenceResult(String mode, String variant) throws Exception {
        Map<String, Object> params = Map.of("mode", mode, "elements", 20_000, "fields", 300, "opsPerTrial", 2);
        ScenarioResult r = run(AllocationScenario.ID, variant, params, 2, 0);

        assertThat(r.invariant().held()).isTrue();
        assertThat(r.trials()).hasSize(2).allSatisfy(t -> {
            assertThat(t.metrics()).containsKeys("msPerOp", "allocatedMbPerOp", "gcCount", "gcPauseMs");
            assertThat(t.metrics().get("allocatedMbPerOp")).isGreaterThanOrEqualTo(0.0);
        });
        Map<String, Object> d = jvm(r);
        assertThat(d.get("kind")).isEqualTo("allocation");
        assertThat(d.get("jfr")).isInstanceOf(JfrSummary.class);
        assertThat(r.observations()).anySatisfy(o -> assertThat(o).startsWith("Exact allocation by the measuring thread"));
    }

    @Test
    void allocationHeavyVariantsAllocateMoreThanTheirLightCounterparts() throws Exception {
        // a property of the code, not of timing: boxing and repeated copying allocate. No warm-up, so
        // the first operations run before C2 could scalar-replace any of the boxes.
        double boxed = allocatedPerOp("aggregate", "boxed-stream");
        double loop = allocatedPerOp("aggregate", "primitive-loop");
        double concat = allocatedPerOp("render", "concat-in-loop");
        double presized = allocatedPerOp("render", "presized-builder");
        assertThat(boxed).isGreaterThan(loop);
        assertThat(concat).isGreaterThan(presized * 10);
    }

    private double allocatedPerOp(String mode, String variant) throws Exception {
        ScenarioResult r = run(AllocationScenario.ID, variant,
                Map.of("mode", mode, "elements", 50_000, "fields", 2_000, "opsPerTrial", 3), 1, 0);
        return r.trials().getFirst().metrics().get("allocatedMbPerOp");
    }

    // ---- lock contention -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"synchronized", "reentrant-lock", "striped"})
    void everyContentionVariantKeepsCountAndChecksum(String variant) throws Exception {
        ScenarioResult r = run(LockContentionScenario.ID, variant,
                Map.of("threads", 3, "incrementsPerThread", 20_000, "workRounds", 5), 2, 0);

        assertThat(r.invariant().held()).isTrue();
        assertThat(r.trials()).hasSize(2).allSatisfy(t -> assertThat(t.metrics()).containsKeys("elapsedMs", "mopsPerSec"));
        Map<String, Object> d = jvm(r);
        assertThat(d.get("kind")).isEqualTo("contention");
        JfrSummary jfr = (JfrSummary) d.get("jfr");
        assertThat(jfr.locks().monitorEnter().thresholdMs()).isEqualTo(0.1);
        // any contention JFR attributes to application code is on the accumulator the variant uses
        assertThat(jfr.locks().monitorEnter().rows()).filteredOn(row -> row.appFrame() != null)
                .allSatisfy(row -> assertThat(row.lockClass()).contains("LockContentionScenario$"));
    }

    @Test
    void theRawRecordingStaysInTheWorkDirectory() throws Exception {
        run(LockContentionScenario.ID, "striped", Map.of("threads", 2, "incrementsPerThread", 1_000, "workRounds", 0), 1, 0);
        assertThat(work.resolve("contention.jfr")).exists();
        assertThat(Files.size(work.resolve("contention.jfr"))).isPositive();
    }
}
