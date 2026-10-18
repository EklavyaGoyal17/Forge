package dev.forge.lab.scenarios;

import dev.forge.lab.scenario.ParameterSpec;
import dev.forge.lab.scenario.ParameterValidator;
import dev.forge.lab.scenario.ScenarioCatalog;
import dev.forge.lab.scenario.ScenarioDescriptor;
import dev.forge.lab.scenario.SourceSnippets;
import dev.forge.lab.scenario.VariantSpec;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogTest {

    @Test
    void everyVariantResolvesToTheSourceThatRuns() {
        for (ScenarioDescriptor d : ScenarioCatalog.descriptors()) {
            for (VariantSpec v : d.variants()) {
                assertThat(SourceSnippets.resolve(v.sourceRef()))
                        .as("source for %s / %s", d.id(), v.id())
                        .isPresent()
                        .get().asString().isNotBlank();
            }
        }
    }

    @Test
    void snippetContainsTheActualImplementation() {
        String src = SourceSnippets.resolve(
                "dev.forge.lab.scenarios.concurrency.LostUpdateScenario#unsynchronized").orElseThrow();
        assertThat(src).contains("class UnsynchronizedBalance").contains("balance = current + 1;");
        assertThat(src).doesNotContain("forge:snippet").doesNotContain("forge:end");
    }

    @Test
    void defaultsAreValidForEveryVariant() {
        for (ScenarioDescriptor d : ScenarioCatalog.descriptors()) {
            for (VariantSpec v : d.variants()) {
                var r = ParameterValidator.validate(d, v.id(), Map.of(), null, null);
                assertThat(r.errors()).as("%s/%s", d.id(), v.id()).isEmpty();
            }
        }
    }

    @Test
    void validatorRejectsOutOfBoundsUnknownAndWrongTypes() {
        ScenarioDescriptor d = ScenarioCatalog.find("concurrency.lost-update").orElseThrow().descriptor();
        Map<String, Object> params = new HashMap<>();
        params.put("threads", 1_000);       // above max 64
        params.put("mode", "chaos");        // not an option
        params.put("rm -rf", 1);            // unknown
        params.put("depositsPerThread", 1.5);
        var r = ParameterValidator.validate(d, "nope", params, 10_000, -1);
        assertThat(r.errors()).extracting(ParameterValidator.FieldError::field)
                .contains("variant", "params.threads", "params.mode", "params.rm -rf",
                        "params.depositsPerThread", "trials", "warmupTrials");
    }

    @Test
    void integerParamsAcceptWholeDoublesFromJson() {
        ScenarioDescriptor d = ScenarioCatalog.find("concurrency.lost-update").orElseThrow().descriptor();
        var r = ParameterValidator.validate(d, "atomic-cas", Map.of("threads", 8.0), 5, 0);
        assertThat(r.errors()).isEmpty();
        assertThat(r.params().get("threads")).isEqualTo(8L);
    }

    @Test
    void parameterSpecRejectsInconsistentBounds() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ParameterSpec.integer("x", "x", 5, 10, 1, null, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
