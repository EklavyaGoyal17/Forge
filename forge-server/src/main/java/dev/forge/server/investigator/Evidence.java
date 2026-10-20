package dev.forge.server.investigator;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Evidence the investigator may use: only facts read from stored records, each with a citable id. */
public final class Evidence {

    private Evidence() {
    }

    /**
     * @param id    citation id, e.g. {@code R3.m.latencyP99Ms} — stable for the same stored data
     * @param kind  EXPERIMENT, NOTES (the user's own writing — not a measurement), SCENARIO, RUN, ENVIRONMENT,
     *              METRIC, INVARIANT, OBSERVATION, DETAIL, COMPARISON, REGRESSION, REVIEW
     * @param runSeq the run the item is about, if any
     * @param text  the fact as text, with the numbers exactly as stored
     */
    public record Item(String id, String kind, String title, Integer runSeq, String text) {
    }

    public record Bundle(UUID experimentId, String scenarioId, String scenarioTitle, List<Item> items) {

        public Map<String, Item> byId() {
            Map<String, Item> m = new java.util.LinkedHashMap<>();
            items.forEach(i -> m.put(i.id(), i));
            return m;
        }

        /** All evidence as one plain-text document, one item per block, for a model prompt. */
        public String render() {
            StringBuilder sb = new StringBuilder();
            for (Item i : items) {
                sb.append('[').append(i.id()).append("] ").append(i.kind()).append(" — ").append(i.title()).append('\n')
                        .append(i.text()).append("\n\n");
            }
            return sb.toString();
        }
    }

    /**
     * One statement of the rule-based analyser.
     *
     * @param kind OBSERVATION (stated directly by the evidence), HYPOTHESIS (a possible explanation, not established),
     *             CAVEAT (a reason to distrust or limit a conclusion), NEXT_EXPERIMENT (what to run to learn more)
     * @param rule the rule that produced it, so every statement is traceable to code
     */
    public record Finding(String kind, String rule, String text, List<String> citations) {
    }
}
