package dev.forge.lab.scenario;

import java.util.List;

/**
 * A bounded, typed scenario parameter.
 *
 * @param appliesToModes if non-empty, the parameter is only meaningful when the {@code mode}
 *                       parameter has one of these values (the UI hides it otherwise; it is still
 *                       validated and defaulted).
 */
public record ParameterSpec(
        String name,
        String label,
        Type type,
        Object defaultValue,
        Long min,
        Long max,
        List<String> options,
        String unit,
        String description,
        List<String> appliesToModes) {

    public enum Type { INTEGER, ENUM, BOOLEAN }

    public ParameterSpec {
        options = options == null ? List.of() : List.copyOf(options);
        appliesToModes = appliesToModes == null ? List.of() : List.copyOf(appliesToModes);
        switch (type) {
            case INTEGER -> {
                if (min == null || max == null || min > max) {
                    throw new IllegalArgumentException(name + ": INTEGER parameters need min <= max");
                }
                long d = ((Number) defaultValue).longValue();
                if (d < min || d > max) {
                    throw new IllegalArgumentException(name + ": default outside bounds");
                }
            }
            case ENUM -> {
                if (options.isEmpty() || !options.contains(defaultValue)) {
                    throw new IllegalArgumentException(name + ": ENUM default must be one of the options");
                }
            }
            case BOOLEAN -> {
                if (!(defaultValue instanceof Boolean)) {
                    throw new IllegalArgumentException(name + ": BOOLEAN default must be a boolean");
                }
            }
        }
    }

    public static ParameterSpec integer(String name, String label, long def, long min, long max, String unit, String description) {
        return new ParameterSpec(name, label, Type.INTEGER, def, min, max, List.of(), unit, description, List.of());
    }

    public static ParameterSpec choice(String name, String label, String def, List<String> options, String description) {
        return new ParameterSpec(name, label, Type.ENUM, def, null, null, options, null, description, List.of());
    }

    public static ParameterSpec bool(String name, String label, boolean def, String description) {
        return new ParameterSpec(name, label, Type.BOOLEAN, def, null, null, List.of(), null, description, List.of());
    }

    public ParameterSpec onlyFor(String... modes) {
        return new ParameterSpec(name, label, type, defaultValue, min, max, options, unit, description, List.of(modes));
    }
}
