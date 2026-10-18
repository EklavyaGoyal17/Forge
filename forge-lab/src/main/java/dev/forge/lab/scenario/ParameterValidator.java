package dev.forge.lab.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates user-supplied parameters against a descriptor and fills in defaults.
 *
 * <p>Used both by the server (to reject bad input with field-level errors before anything is
 * queued) and by the runner (defence in depth: the child JVM never trusts its stdin).
 */
public final class ParameterValidator {

    /** Absolute ceilings that apply to every scenario regardless of its descriptor. */
    public static final int MAX_TRIALS = 1_000;
    public static final int MAX_WARMUP = 100;

    private ParameterValidator() {
    }

    public record FieldError(String field, String message) {
    }

    public record Result(Map<String, Object> params, int trials, int warmupTrials, List<FieldError> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public static Result validate(ScenarioDescriptor d, String variant, Map<String, ?> raw, Integer trials, Integer warmup) {
        List<FieldError> errors = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, ?> input = raw == null ? Map.of() : raw;

        if (variant == null || d.variant(variant).isEmpty()) {
            errors.add(new FieldError("variant", "unknown variant '" + variant + "'; expected one of "
                    + d.variants().stream().map(VariantSpec::id).toList()));
        }

        for (String key : input.keySet()) {
            if (d.parameter(key).isEmpty()) {
                errors.add(new FieldError("params." + key, "unknown parameter"));
            }
        }

        for (ParameterSpec p : d.parameters()) {
            Object value = input.containsKey(p.name()) ? input.get(p.name()) : p.defaultValue();
            String field = "params." + p.name();
            switch (p.type()) {
                case INTEGER -> {
                    Long n = asLong(value);
                    if (n == null) {
                        errors.add(new FieldError(field, "must be an integer"));
                    } else if (n < p.min() || n > p.max()) {
                        errors.add(new FieldError(field, "must be between " + p.min() + " and " + p.max()));
                    } else {
                        out.put(p.name(), n);
                    }
                }
                case ENUM -> {
                    if (!(value instanceof String s) || !p.options().contains(s)) {
                        errors.add(new FieldError(field, "must be one of " + p.options()));
                    } else {
                        out.put(p.name(), s);
                    }
                }
                case BOOLEAN -> {
                    if (!(value instanceof Boolean b)) {
                        errors.add(new FieldError(field, "must be true or false"));
                    } else {
                        out.put(p.name(), b);
                    }
                }
            }
        }

        if (variant != null && out.containsKey("mode")) {
            d.variant(variant).filter(v -> !v.supportsMode(out.get("mode"))).ifPresent(v -> errors.add(new FieldError(
                    "variant", "variant '" + v.id() + "' does not support mode '" + out.get("mode") + "'; supported: " + v.modes())));
        }

        ScenarioDescriptor.TrialPolicy policy = d.trials();
        int t = trials == null ? policy.defaultTrials() : trials;
        int w = warmup == null ? policy.defaultWarmup() : warmup;
        int maxT = Math.min(policy.maxTrials(), MAX_TRIALS);
        int maxW = Math.min(policy.maxWarmup(), MAX_WARMUP);
        if (t < 1 || t > maxT) {
            errors.add(new FieldError("trials", "must be between 1 and " + maxT));
        }
        if (w < 0 || w > maxW) {
            errors.add(new FieldError("warmupTrials", "must be between 0 and " + maxW));
        }
        return new Result(Map.copyOf(out), t, w, List.copyOf(errors));
    }

    private static Long asLong(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Number n) {
            double dv = n.doubleValue();
            if (dv == Math.rint(dv) && Math.abs(dv) < 9.0e15) {
                return (long) dv;
            }
        }
        return null;
    }
}
