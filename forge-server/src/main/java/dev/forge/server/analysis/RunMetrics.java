package dev.forge.server.analysis;

import dev.forge.lab.stats.Summary;
import dev.forge.server.run.RunRecord;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Extracts per-metric samples from a stored run result. */
public final class RunMetrics {

    private RunMetrics() {
    }

    /** metric name → per-trial values, in trial order. Trials missing a metric are skipped. */
    public static Map<String, double[]> samples(RunRecord run) {
        Map<String, List<Double>> acc = new LinkedHashMap<>();
        JsonNode trials = run.result() == null ? null : run.result().get("trials");
        if (trials != null && trials.isArray()) {
            for (JsonNode t : trials) {
                JsonNode metrics = t.get("metrics");
                if (metrics == null) {
                    continue;
                }
                for (var e : metrics.properties()) {
                    if (e.getValue().isNumber()) {
                        acc.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue().asDouble());
                    }
                }
            }
        }
        Map<String, double[]> out = new LinkedHashMap<>();
        acc.forEach((k, v) -> out.put(k, v.stream().mapToDouble(Double::doubleValue).toArray()));
        return out;
    }

    public static Map<String, Summary> summaries(RunRecord run) {
        Map<String, Summary> out = new LinkedHashMap<>();
        samples(run).forEach((k, v) -> out.put(k, Summary.of(v)));
        return out;
    }

    /** {@code held} of the stored invariant summary, or null. */
    public static Boolean invariantHeld(RunRecord run) {
        JsonNode inv = run.result() == null ? null : run.result().get("invariant");
        if (inv == null || inv.get("held") == null || inv.get("held").isNull()) {
            return null;
        }
        return inv.get("held").asBoolean();
    }

    public static String invariantText(RunRecord run) {
        JsonNode inv = run.result() == null ? null : run.result().get("invariant");
        if (inv == null || inv.get("held") == null || inv.get("held").isNull()) {
            return "not applicable";
        }
        return (inv.get("held").asBoolean() ? "held" : "VIOLATED") + " (" + inv.get("violations").asInt() + " violations in "
                + inv.get("checked").asInt() + " checks)";
    }
}
