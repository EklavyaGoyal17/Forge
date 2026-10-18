package dev.forge.lab.scenario;

import java.util.List;
import java.util.Optional;

/**
 * Everything the platform needs to know about a scenario without executing it: how to render the
 * configuration form, how to validate input, which metric to compare, and what the scenario is
 * meant to teach.
 *
 * <p>The descriptor is the single source of truth for the UI form, server-side validation and
 * runner-side validation, so the three can never disagree.
 */
public record ScenarioDescriptor(
        String id,
        int version,
        Lab lab,
        String title,
        String summary,
        String question,
        String invariant,
        List<ParameterSpec> parameters,
        List<VariantSpec> variants,
        List<MetricSpec> metrics,
        String primaryMetric,
        TrialPolicy trials,
        int defaultTimeoutSeconds,
        String explanation,
        List<String> caveats) {

    public ScenarioDescriptor {
        parameters = List.copyOf(parameters);
        variants = List.copyOf(variants);
        metrics = List.copyOf(metrics);
        caveats = List.copyOf(caveats);
        if (metrics.stream().noneMatch(m -> m.name().equals(primaryMetric))) {
            throw new IllegalArgumentException("primaryMetric '" + primaryMetric + "' is not declared in " + id);
        }
    }

    public Optional<VariantSpec> variant(String variantId) {
        return variants.stream().filter(v -> v.id().equals(variantId)).findFirst();
    }

    public Optional<ParameterSpec> parameter(String name) {
        return parameters.stream().filter(p -> p.name().equals(name)).findFirst();
    }

    public Optional<MetricSpec> metric(String name) {
        return metrics.stream().filter(m -> m.name().equals(name)).findFirst();
    }

    /** Bounds on how many measured and warm-up trials a run may request. */
    public record TrialPolicy(int defaultTrials, int maxTrials, int defaultWarmup, int maxWarmup) {
        public TrialPolicy {
            if (defaultTrials < 1 || defaultTrials > maxTrials || defaultWarmup < 0 || defaultWarmup > maxWarmup) {
                throw new IllegalArgumentException("inconsistent trial policy");
            }
        }
    }
}
