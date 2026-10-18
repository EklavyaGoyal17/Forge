package dev.forge.lab.scenario;

/** A per-trial measurement a scenario reports. */
public record MetricSpec(String name, String label, String unit, Direction direction, String description) {

    public enum Direction {
        LOWER_IS_BETTER,
        HIGHER_IS_BETTER,
        /** Informational (e.g. a count that should simply be zero or equal to an expected value). */
        NEUTRAL
    }
}
