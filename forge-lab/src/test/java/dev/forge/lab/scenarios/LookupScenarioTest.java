package dev.forge.lab.scenarios;

import dev.forge.lab.scenario.ParameterValidator;
import dev.forge.lab.scenario.RunContext;
import dev.forge.lab.scenario.RunSpec;
import dev.forge.lab.scenario.Scenario;
import dev.forge.lab.scenario.ScenarioCatalog;
import dev.forge.lab.scenario.ScenarioResult;
import dev.forge.lab.scenarios.algorithms.LookupBenchmark;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LookupScenarioTest {

    @TempDir
    Path work;

    @Test
    void benchmarkVariantsAgreeOnHitCount() {
        LookupBenchmark b = new LookupBenchmark();
        b.size = 500;
        b.hitPercent = 30;
        b.seed = 11;
        b.setup();
        int expected = b.hashSet();
        assertThat(b.linearArray()).isEqualTo(expected);
        assertThat(b.linearList()).isEqualTo(expected);
        assertThat(b.binarySearch()).isEqualTo(expected);
        // roughly 30% of 1024 queries hit
        assertThat(expected).isBetween(200, 420);
    }

    @Test
    void runsThroughJmhAndReportsOneTrialPerMeasurementIteration() throws Exception {
        Scenario s = ScenarioCatalog.find("algorithms.lookup").orElseThrow();
        var v = ParameterValidator.validate(s.descriptor(), "hash-set",
                Map.of("size", 100, "iterationMs", 100, "forks", 1), 2, 1);
        assertThat(v.errors()).isEmpty();
        RunSpec spec = new RunSpec("t", s.descriptor().id(), 1, "hash-set", v.params(), 3, v.trials(), v.warmupTrials());
        ScenarioResult r = s.run(spec, RunContext.noop(work));
        assertThat(r.trials()).hasSize(2);
        assertThat(r.trials()).allSatisfy(t -> assertThat(t.metrics().get("nsPerLookup")).isPositive());
        assertThat(r.details()).containsKey("jmh");
    }
}
