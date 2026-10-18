package dev.forge.lab.scenario;

import java.util.Map;

/**
 * The complete, reproducible input of one run. Together with the scenario version and the captured
 * environment this is what a run is a function of.
 *
 * @param params already validated and defaulted (see {@link ParameterValidator}).
 */
public record RunSpec(
        String runId,
        String scenarioId,
        int scenarioVersion,
        String variant,
        Map<String, Object> params,
        long seed,
        int trials,
        int warmupTrials) {

    public RunSpec {
        params = Map.copyOf(params);
    }

    public long longParam(String name) {
        Object v = params.get(name);
        if (v instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalArgumentException("parameter '" + name + "' is missing or not numeric");
    }

    public int intParam(String name) {
        return Math.toIntExact(longParam(name));
    }

    public String stringParam(String name) {
        Object v = params.get(name);
        if (v instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("parameter '" + name + "' is missing or not a string");
    }

    public boolean boolParam(String name) {
        Object v = params.get(name);
        if (v instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException("parameter '" + name + "' is missing or not a boolean");
    }
}
