package dev.forge.lab.database;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures {@code EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)} for one statement and summarises it.
 *
 * <p>ANALYZE executes the statement for real (do not use it on data-changing SQL you want to
 * keep) and adds per-node timing overhead, so its execution time is reported next to — never
 * instead of — client-measured latency. Buffer counts at the root node include all child nodes.
 */
public final class Explain {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    /** Plans larger than this are cut, with a note, to keep results small. */
    static final int MAX_NODES = 200;

    private Explain() {
    }

    /**
     * @param plan       the root plan node as plain maps/lists (keys as PostgreSQL emits them)
     * @param nodes      one line per node in depth-first order, e.g. "Index Only Scan using x on txn"
     * @param sharedHit  shared buffers found in PostgreSQL's cache (root node, includes children)
     * @param sharedRead shared buffers read from the OS (page cache or disk — PostgreSQL cannot tell)
     */
    public record Result(Map<String, Object> plan, double executionMs, double planningMs, long sharedHit,
                         long sharedRead, long rootRows, List<String> nodes, boolean truncated) {

        public Map<String, Object> toMap(String label) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", label);
            m.put("executionMs", round(executionMs));
            m.put("planningMs", round(planningMs));
            m.put("sharedHitBlocks", sharedHit);
            m.put("sharedReadBlocks", sharedRead);
            m.put("rootRows", rootRows);
            m.put("nodes", nodes);
            m.put("truncated", truncated);
            m.put("plan", plan);
            return m;
        }

        /**
         * The scan nodes, outermost first (e.g. "Bitmap Heap Scan on txn ← Bitmap Index Scan using
         * txn_account_idx"): how the planner chose to reach the data.
         */
        public String accessPath() {
            List<String> scans = nodes.stream().map(String::strip).filter(n -> n.contains("Scan")).toList();
            if (scans.isEmpty()) {
                return nodes.isEmpty() ? "?" : nodes.get(0).strip();
            }
            return String.join(" ← ", scans);
        }
    }

    public static Result analyze(Connection c, String sql) throws SQLException {
        String json;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("explain (analyze, buffers, format json) " + sql)) {
            rs.next();
            json = rs.getString(1);
        }
        return parse(json);
    }

    static Result parse(String json) {
        JsonNode top = MAPPER.readTree(json).get(0);
        JsonNode root = top.get("Plan");
        List<String> nodes = new ArrayList<>();
        int[] budget = {MAX_NODES};
        boolean[] truncated = {false};
        Map<String, Object> plan = copy(root, nodes, budget, truncated, 0);
        return new Result(plan,
                top.path("Execution Time").asDouble(),
                top.path("Planning Time").asDouble(),
                root.path("Shared Hit Blocks").asLong(),
                root.path("Shared Read Blocks").asLong(),
                root.path("Actual Rows").asLong(),
                nodes, truncated[0]);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(JsonNode node, List<String> nodes, int[] budget, boolean[] truncated, int depth) {
        budget[0]--;
        nodes.add("  ".repeat(depth) + describe(node));
        Map<String, Object> m = new LinkedHashMap<>(MAPPER.convertValue(node, Map.class));
        m.remove("Plans");
        JsonNode children = node.get("Plans");
        if (children != null && children.isArray()) {
            List<Map<String, Object>> kids = new ArrayList<>();
            for (JsonNode child : children) {
                if (budget[0] <= 0) {
                    truncated[0] = true;
                    break;
                }
                kids.add(copy(child, nodes, budget, truncated, depth + 1));
            }
            m.put("Plans", kids);
        }
        return m;
    }

    static String describe(JsonNode n) {
        StringBuilder sb = new StringBuilder(n.path("Node Type").asString("?"));
        if (n.has("Index Name")) {
            sb.append(" using ").append(n.get("Index Name").asString());
        }
        if (n.has("Relation Name")) {
            sb.append(" on ").append(n.get("Relation Name").asString());
        }
        return sb.toString();
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
