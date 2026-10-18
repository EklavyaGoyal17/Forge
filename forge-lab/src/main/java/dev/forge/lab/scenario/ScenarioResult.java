package dev.forge.lab.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a scenario measured. Only facts belong here: per-trial measurements, invariant checks,
 * and observations that are direct statements about the data ("violated in 47 of 50 trials"),
 * never root-cause claims.
 *
 * @param details scenario-specific structured evidence (explorer traces, JMH iteration data, ...),
 *                keyed by a type name the UI knows how to render.
 */
public record ScenarioResult(
        List<Trial> trials,
        InvariantSummary invariant,
        List<String> observations,
        Map<String, Object> details) {

    public ScenarioResult {
        trials = List.copyOf(trials);
        observations = List.copyOf(observations);
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    /** One measured repetition. */
    public record Trial(int index, Map<String, Double> metrics, Boolean invariantHeld, String note) {
        public Trial {
            metrics = Map.copyOf(metrics);
        }
    }

    /**
     * @param held {@code true} only if every checked trial held; {@code null} if not applicable.
     */
    public record InvariantSummary(String description, int checked, int violations, Boolean held) {
        public static InvariantSummary notApplicable() {
            return new InvariantSummary(null, 0, 0, null);
        }

        public static InvariantSummary of(String description, List<Trial> trials) {
            int checked = 0;
            int violations = 0;
            for (Trial t : trials) {
                if (t.invariantHeld() != null) {
                    checked++;
                    if (!t.invariantHeld()) {
                        violations++;
                    }
                }
            }
            return new InvariantSummary(description, checked, violations, checked == 0 ? null : violations == 0);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<Trial> trials = new ArrayList<>();
        private final List<String> observations = new ArrayList<>();
        private final Map<String, Object> details = new LinkedHashMap<>();
        private InvariantSummary invariant = InvariantSummary.notApplicable();

        public Builder trial(Trial trial) {
            trials.add(trial);
            return this;
        }

        public Builder observation(String observation) {
            observations.add(observation);
            return this;
        }

        public Builder detail(String key, Object value) {
            details.put(key, value);
            return this;
        }

        public Builder invariant(InvariantSummary summary) {
            this.invariant = summary;
            return this;
        }

        public List<Trial> trials() {
            return trials;
        }

        public ScenarioResult build() {
            return new ScenarioResult(trials, invariant, observations, details);
        }
    }
}
