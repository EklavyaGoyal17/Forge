package dev.forge.lab.scenarios.database;

import dev.forge.lab.database.Explain;
import dev.forge.lab.database.LabSession;
import dev.forge.lab.database.TransactionHistory;
import dev.forge.lab.scenario.Lab;
import dev.forge.lab.scenario.MetricSpec;
import dev.forge.lab.scenario.MetricSpec.Direction;
import dev.forge.lab.scenario.ParameterSpec;
import dev.forge.lab.scenario.RunContext;
import dev.forge.lab.scenario.RunSpec;
import dev.forge.lab.scenario.Scenario;
import dev.forge.lab.scenario.ScenarioDescriptor;
import dev.forge.lab.scenario.ScenarioResult;
import dev.forge.lab.scenario.ScenarioResult.InvariantSummary;
import dev.forge.lab.scenario.ScenarioResult.Trial;
import dev.forge.lab.scenario.VariantSpec;
import dev.forge.lab.stats.Summary;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Database Studio: "The Query That Wouldn't Scale" — fetching page N of a transaction feed with
 * {@code OFFSET} versus a keyset (seek) predicate, with the same index available to both.
 */
public final class PaginationScenario implements Scenario {

    public static final String ID = "database.pagination";
    private static final String SRC = PaginationScenario.class.getName() + "#";

    /** Both variants get this index, so the comparison is about the query shape, not the schema. */
    static final String FEED_INDEX = "create index txn_feed_idx on txn (created_at, id)";

    // forge:snippet offset
    static final String OFFSET_PAGE = """
            select id, created_at, amount
            from txn
            order by created_at, id
            limit ? offset ?""";       // offset = (page - 1) × pageSize: every earlier row is read and thrown away
    // forge:end

    // forge:snippet keyset
    static final String KEYSET_PAGE = """
            select id, created_at, amount
            from txn
            where (created_at, id) > (?, ?)   -- the last row of the previous page
            order by created_at, id
            limit ?""";                       // the index seeks straight to the cursor
    // forge:end

    private static final ScenarioDescriptor DESCRIPTOR = new ScenarioDescriptor(
            ID, 1, Lab.DATABASE,
            "The Query That Wouldn't Scale: OFFSET vs keyset pagination",
            "Page through a seeded transaction feed ordered by (created_at, id), deep into the history. OFFSET reads and discards every earlier row; a keyset predicate seeks to where the previous page ended. Both have the same index.",
            "Why does page 2,000 take so much longer than page 1 with OFFSET, and what does a keyset cursor change?",
            "A keyset page contains exactly the same rows, in the same order, as the OFFSET page at the same position.",
            List.of(
                    TransactionHistory.dataSeedParameter(),
                    ParameterSpec.integer("rows", "Transactions", 300_000, 10_000, 2_000_000, "rows",
                            "Rows seeded into txn (deterministic from the run seed)."),
                    ParameterSpec.integer("pageSize", "Page size", 50, 10, 500, "rows", "Rows per page."),
                    ParameterSpec.integer("page", "Start page", 2_000, 1, 100_000, "page",
                            "Each trial reads consecutive pages starting here (must lie within the data)."),
                    ParameterSpec.integer("pagesPerTrial", "Pages per trial", 20, 5, 200, "pages",
                            "Consecutive pages fetched per trial, each timed from the client.")),
            List.of(
                    new VariantSpec("offset", "LIMIT … OFFSET", "Skips (page − 1) × pageSize rows on every request.", null, SRC + "offset"),
                    new VariantSpec("keyset", "Keyset / seek", "Continues after the previous page's last (created_at, id).", null, SRC + "keyset")),
            List.of(
                    new MetricSpec("medianPageMs", "Median page time", "ms", Direction.LOWER_IS_BETTER,
                            "Median client-measured time to fetch one page (execute + read all rows)."),
                    new MetricSpec("p95PageMs", "p95 page time", "ms", Direction.LOWER_IS_BETTER, "95th percentile of the trial's page fetches."),
                    new MetricSpec("explainExecutionMs", "Server execution (EXPLAIN ANALYZE)", "ms", Direction.LOWER_IS_BETTER,
                            "PostgreSQL's execution time for the trial's first page, including instrumentation overhead."),
                    new MetricSpec("rowsScanned", "Rows read by scans", "rows", Direction.LOWER_IS_BETTER,
                            "Rows produced by scan nodes for the trial's first page (index entries and table rows of each scan node)."),
                    new MetricSpec("sharedHitBlocks", "Buffers hit", "8 kB blocks", Direction.LOWER_IS_BETTER, "Shared buffers touched for the first page."),
                    new MetricSpec("pageMismatches", "Page mismatches", "pages", Direction.LOWER_IS_BETTER,
                            "Pages where the keyset and OFFSET answers differ (the invariant).")),
            "medianPageMs",
            new ScenarioDescriptor.TrialPolicy(5, 30, 1, 5),
            300,
            """
            OFFSET n does not tell PostgreSQL where page n starts: it has to produce the first n rows in order and \
            throw them away. With the (created_at, id) index those rows come cheaply off the index, but they still \
            have to be walked — so the cost of a page grows linearly with how deep it is.

            A keyset (or seek) page asks for "the next pageSize rows after the last one I saw". With an index in the \
            same order, PostgreSQL descends the B-tree to that position and reads only pageSize entries, at any depth.

            The trade-offs are real: keyset paging needs a unique, ordered cursor (hence the id tie-breaker), cannot \
            jump to an arbitrary page number, and the row-value comparison (created_at, id) > (…) must match the \
            index order exactly to be usable. In return, results do not shift when rows are inserted earlier in the feed.""",
            List.of(
                    "The keyset cursor for the start page is obtained once with an untimed OFFSET query — a real application would carry it from the previous page.",
                    "Both variants use the same (created_at, id) index; without it, OFFSET pages are slower still and the keyset predicate cannot seek.",
                    "The data fits in memory; deeper OFFSET pages on a table larger than RAM would also pay for I/O.",
                    "EXPLAIN ANALYZE adds per-node timing overhead; client time is the primary measure.",
                    "Rows read by scans counts what scan nodes produced, so a bitmap scan's index entries and table rows are both counted.",
                    "Client time includes the round trip to PostgreSQL (a VM boundary with Docker Desktop)."));

    @Override
    public ScenarioDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ScenarioResult run(RunSpec spec, RunContext ctx) throws Exception {
        boolean keyset = switch (spec.variant()) {
            case "keyset" -> true;
            case "offset" -> false;
            default -> throw new IllegalArgumentException("unknown variant " + spec.variant());
        };
        int rows = spec.intParam("rows");
        int pageSize = spec.intParam("pageSize");
        int startPage = spec.intParam("page");
        int pagesPerTrial = spec.intParam("pagesPerTrial");
        long lastPage = (long) (startPage + pagesPerTrial - 1);
        if (lastPage * pageSize > rows) {
            throw new IllegalArgumentException(String.format(
                    "pages %d–%d of %d rows need %d rows, but only %d are seeded; lower the start page or raise rows",
                    startPage, lastPage, pageSize, lastPage * pageSize, rows));
        }

        ScenarioResult.Builder result = ScenarioResult.builder();
        try (LabSession session = LabSession.open(ctx, spec.runId())) {
            Connection c = session.connection();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("postgres", session.serverFacts());
            ctx.info("seeding " + rows + " transactions in schema " + session.schema());
            TransactionHistory.Dataset data = TransactionHistory.seed(c, spec.longParam("dataSeed"), Math.max(100, rows / 50), rows);
            details.put("dataset", data.toMap());
            try (Statement st = c.createStatement()) {
                st.execute(FEED_INDEX);
                st.execute("analyze txn");
            }
            details.put("index", Map.of("name", "txn_feed_idx", "ddl", FEED_INDEX,
                    "sizeMb", Math.round(TransactionHistory.relationMb(c, "txn_feed_idx") * 100) / 100.0));
            details.put("queries", List.of(Map.of("label", keyset ? "keyset page" : "offset page", "sql", keyset ? KEYSET_PAGE : OFFSET_PAGE)));

            List<Map<String, Object>> plans = new ArrayList<>();
            int total = spec.warmupTrials() + spec.trials();
            try (PreparedStatement offsetPs = c.prepareStatement(OFFSET_PAGE);
                 PreparedStatement keysetPs = c.prepareStatement(KEYSET_PAGE)) {
                for (int t = 0; t < total; t++) {
                    ctx.checkCancelled();
                    boolean warmup = t < spec.warmupTrials();
                    Page cursor = startPage == 1 ? null : offsetPage(offsetPs, startPage - 1, pageSize);
                    double[] ms = new double[pagesPerTrial];
                    int mismatches = 0;
                    Page first = null;
                    Page previous = cursor;
                    for (int i = 0; i < pagesPerTrial; i++) {
                        int page = startPage + i;
                        long t0 = System.nanoTime();
                        Page p = keyset ? keysetPage(keysetPs, previous, pageSize) : offsetPage(offsetPs, page, pageSize);
                        ms[i] = (System.nanoTime() - t0) / 1e6;
                        // invariant (untimed): the other method must return the same page
                        Page check = keyset ? offsetPage(offsetPs, page, pageSize) : keysetPage(keysetPs, previous, pageSize);
                        if (!p.ids().equals(check.ids())) {
                            mismatches++;
                        }
                        if (i == 0) {
                            first = p;
                        }
                        previous = p;
                    }
                    Explain.Result ex = Explain.analyze(c, keyset ? keysetLiteral(cursor, pageSize) : offsetLiteral(startPage, pageSize));
                    if (warmup) {
                        ctx.progress(t + 1, total, "warm-up");
                        continue;
                    }
                    int index = t - spec.warmupTrials();
                    Summary s = Summary.of(ms);
                    Map<String, Double> m = new LinkedHashMap<>();
                    m.put("medianPageMs", round(s.median()));
                    m.put("p95PageMs", round(s.p95()));
                    m.put("explainExecutionMs", round(ex.executionMs()));
                    m.put("rowsScanned", (double) ex.scannedRows());
                    m.put("sharedHitBlocks", (double) ex.sharedHit());
                    m.put("pageMismatches", (double) mismatches);
                    result.trial(new Trial(index, m, mismatches == 0,
                            ex.accessPath() + (first == null ? "" : "; first id " + first.ids().get(0))));
                    if (index == 0) {
                        plans.add(ex.toMap((keyset ? "keyset" : "offset") + " page " + startPage + ", trial 1"));
                    }
                    ctx.progress(t + 1, total, "measured");
                }

                details.put("plans", plans);
                details.put("sweep", sweep(c, offsetPs, keysetPs, keyset, rows, pageSize, ctx));
            }
            result.detail("database", details);

            List<Trial> trials = result.trials();
            InvariantSummary inv = InvariantSummary.of(DESCRIPTOR.invariant(), trials);
            result.invariant(inv);
            double[] medians = trials.stream().mapToDouble(x -> x.metrics().get("medianPageMs")).sorted().toArray();
            result.observation(String.format("%s pages %d–%d of %d rows: median %.3f ms per page across %d trials (per-trial medians %.3f–%.3f ms).",
                    keyset ? "Keyset" : "OFFSET", startPage, lastPage, pageSize, IndexLookupScenario.median(medians), trials.size(),
                    medians[0], medians[medians.length - 1]));
            result.observation(String.format("For page %d, scan nodes produced %.0f rows to return %d.", startPage,
                    trials.get(0).metrics().get("rowsScanned"), pageSize));
            result.observation("Keyset and OFFSET answers matched on every page in " + (inv.checked() - inv.violations())
                    + " of " + inv.checked() + " trials.");
        }
        return result.build();
    }

    record Page(List<Long> ids, Timestamp lastCreatedAt, long lastId) {
    }

    static Page offsetPage(PreparedStatement ps, long page, int pageSize) throws SQLException {
        ps.setInt(1, pageSize);
        ps.setLong(2, (page - 1) * pageSize);
        return read(ps);
    }

    static Page keysetPage(PreparedStatement ps, Page after, int pageSize) throws SQLException {
        // before the first page: a cursor below every seeded row (history starts in 2024, ids at 1)
        ps.setTimestamp(1, after == null ? new Timestamp(0) : after.lastCreatedAt());
        ps.setLong(2, after == null ? 0 : after.lastId());
        ps.setInt(3, pageSize);
        return read(ps);
    }

    private static Page read(PreparedStatement ps) throws SQLException {
        List<Long> ids = new ArrayList<>();
        Timestamp last = null;
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
                last = rs.getTimestamp(2);
            }
        }
        return new Page(ids, last, ids.isEmpty() ? -1 : ids.get(ids.size() - 1));
    }

    private static String offsetLiteral(long page, int pageSize) {
        return OFFSET_PAGE.replace("limit ? offset ?", "limit " + pageSize + " offset " + (page - 1) * pageSize);
    }

    private static String keysetLiteral(Page after, int pageSize) {
        String bound = after == null ? "(timestamptz '1970-01-01T00:00:00Z', 0)"
                : "(timestamptz '" + after.lastCreatedAt().toInstant() + "', " + after.lastId() + ")";
        return KEYSET_PAGE.replace("(?, ?)", bound).replace("limit ?", "limit " + pageSize);
    }

    /** Page latency at increasing depths for this variant: the curve the scenario is about. */
    private static List<Map<String, Object>> sweep(Connection c, PreparedStatement offsetPs, PreparedStatement keysetPs,
                                                   boolean keyset, int rows, int pageSize, RunContext ctx) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        long maxPage = rows / pageSize;
        for (long page = 1; page <= maxPage; page *= 10) {
            ctx.checkCancelled();
            Page cursor = page == 1 ? null : offsetPage(offsetPs, page - 1, pageSize);
            double[] ms = new double[5];
            for (int r = 0; r < ms.length; r++) {
                long t0 = System.nanoTime();
                if (keyset) {
                    keysetPage(keysetPs, cursor, pageSize);
                } else {
                    offsetPage(offsetPs, page, pageSize);
                }
                ms[r] = (System.nanoTime() - t0) / 1e6;
            }
            Explain.Result ex = Explain.analyze(c, keyset ? keysetLiteral(cursor, pageSize) : offsetLiteral(page, pageSize));
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("page", page);
            p.put("medianMs", round(Summary.of(ms).median()));
            p.put("rowsScanned", ex.scannedRows());
            p.put("explainExecutionMs", round(ex.executionMs()));
            out.add(p);
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
