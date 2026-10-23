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
import dev.forge.lab.scenario.ScenarioResult.Trial;
import dev.forge.lab.scenario.VariantSpec;
import dev.forge.lab.stats.Summary;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Database Studio: how an index changes one query — "this account's transactions in the last N
 * days, oldest first" — from a full table scan to an index-only scan.
 */
public final class IndexLookupScenario implements Scenario {

    public static final String ID = "database.index-lookup";
    private static final String SRC = IndexLookupScenario.class.getName() + "#";

    /** The end of the seeded history; the window is counted back from here. */
    static final Instant HISTORY_END = Instant.parse("2026-01-01T00:00:00Z");

    // forge:snippet query
    static final String QUERY = """
            select created_at, amount
            from txn
            where account_id = ?
              and created_at >= ?
            order by created_at""";
    // forge:end

    // forge:snippet no-index
    /** Nothing beyond the primary key on txn(id). PostgreSQL does not index foreign keys by itself. */
    static final String NO_INDEX = null;
    // forge:end

    // forge:snippet account-index
    static final String ACCOUNT_INDEX = "create index txn_account_idx on txn (account_id)";
    // forge:end

    // forge:snippet composite-index
    /** Equality column first, range/sort column second: rows come out of the index already ordered. */
    static final String COMPOSITE_INDEX = "create index txn_account_created_idx on txn (account_id, created_at)";
    // forge:end

    // forge:snippet covering-index
    /** INCLUDE adds amount to the leaf pages, so the heap need not be visited (index-only scan). */
    static final String COVERING_INDEX = "create index txn_account_created_cov on txn (account_id, created_at) include (amount)";
    // forge:end

    private static final Map<String, String> DDL = new LinkedHashMap<>();

    static {
        DDL.put("no-index", NO_INDEX);
        DDL.put("account-index", ACCOUNT_INDEX);
        DDL.put("composite-index", COMPOSITE_INDEX);
        DDL.put("covering-index", COVERING_INDEX);
    }

    private static final ScenarioDescriptor DESCRIPTOR = new ScenarioDescriptor(
            ID, 1, Lab.DATABASE,
            "Index or no index: one account's recent transactions",
            "A seeded transaction history in a disposable PostgreSQL schema. The same query runs against no index, a single-column index, a composite index and a covering index, with EXPLAIN (ANALYZE, BUFFERS) captured for each trial.",
            "How much of a lookup's cost is finding the rows, and which index shape lets PostgreSQL skip the table entirely?",
            null,
            List.of(
                    TransactionHistory.dataSeedParameter(),
                    ParameterSpec.integer("rows", "Transactions", 500_000, 10_000, 2_000_000, "rows",
                            "Rows seeded into txn (deterministic from the run seed)."),
                    ParameterSpec.integer("accounts", "Accounts", 10_000, 100, 100_000, "accounts",
                            "Accounts the transactions are spread over uniformly; rows ÷ accounts ≈ rows per account."),
                    ParameterSpec.integer("windowDays", "Window", 90, 1, 730, "days",
                            "The query returns the account's transactions from the last N days of the two-year history."),
                    ParameterSpec.integer("queriesPerTrial", "Queries per trial", 100, 10, 1_000, "queries",
                            "Lookups per trial, each for a different (seeded) account, timed from the client.")),
            List.of(
                    new VariantSpec("no-index", "No index", "Only the primary key: every lookup scans txn.", null, SRC + "no-index"),
                    new VariantSpec("account-index", "Index on account_id",
                            "Lets PostgreSQL find the account's rows without scanning the table; the date filter and the sort still happen afterwards.", null, SRC + "account-index"),
                    new VariantSpec("composite-index", "Index on (account_id, created_at)",
                            "Makes the date range a contiguous, ordered slice of the index. Whether PostgreSQL walks it in order or uses a bitmap scan plus a sort is a cost decision — see the plan.", null, SRC + "composite-index"),
                    new VariantSpec("covering-index", "Covering index (… INCLUDE amount)",
                            "Every selected column is in the index, which makes an index-only scan possible (if the visibility map is current).", null, SRC + "covering-index")),
            List.of(
                    new MetricSpec("medianQueryMs", "Median query time", "ms", Direction.LOWER_IS_BETTER,
                            "Median client-measured time of the trial's lookups: execute + fetch every row."),
                    new MetricSpec("p95QueryMs", "p95 query time", "ms", Direction.LOWER_IS_BETTER, "95th percentile of the trial's lookups (client)."),
                    new MetricSpec("explainExecutionMs", "Server execution (EXPLAIN ANALYZE)", "ms", Direction.LOWER_IS_BETTER,
                            "PostgreSQL's own execution time for the probe account, including instrumentation overhead."),
                    new MetricSpec("sharedHitBlocks", "Buffers hit", "8 kB blocks", Direction.LOWER_IS_BETTER,
                            "Shared buffers found in PostgreSQL's cache for the probe lookup."),
                    new MetricSpec("sharedReadBlocks", "Buffers read", "8 kB blocks", Direction.LOWER_IS_BETTER,
                            "Shared buffers read from the operating system (its page cache or disk) for the probe lookup."),
                    new MetricSpec("rowsPerQuery", "Rows per query", "rows", Direction.NEUTRAL, "Mean rows returned per lookup.")),
            "medianQueryMs",
            new ScenarioDescriptor.TrialPolicy(5, 30, 1, 5),
            300,
            """
            Without an index on account_id, PostgreSQL has one way to find an account's rows: read the whole table \
            and keep the matching ones. The work grows with the table, not with the answer.

            A B-tree on account_id jumps to the account's entries, but each entry points into the table (the heap), \
            so PostgreSQL still visits heap pages and then filters by date and sorts. Putting created_at second in \
            the index keeps each account's entries in date order, so the date range becomes a contiguous slice that \
            could be read without a sort. Whether the planner does that, or prefers a bitmap scan (collect the \
            matching row locations, visit each heap page once, then sort), is a cost estimate — the captured plan \
            shows which it chose. INCLUDE (amount) stores the last selected column in the index too, so with an \
            up-to-date visibility map PostgreSQL can answer from the index alone (an index-only scan).

            The point is not that one shape always wins: it is to see what each shape makes possible, what the \
            planner chose for this data, and what that cost.

            Every index costs write time and space — both are measured here — which is why "index everything" is not \
            the answer.""",
            List.of(
                    "The data is small enough to stay in memory: 'buffers read' are mostly the OS page cache, not disk. Tables larger than RAM change the picture — sequential scans become I/O bound and random heap visits get expensive.",
                    "Statistics and the visibility map are fresh (VACUUM ANALYZE right after loading). Real tables with stale statistics or many recent writes may get different plans, and index-only scans degrade until the next vacuum.",
                    "EXPLAIN ANALYZE adds per-node timing overhead; its execution time is reported next to, not instead of, the client-measured time.",
                    "Timed lookups use a prepared statement with bound parameters, like an application. After five executions the JDBC driver switches to a server-side prepared statement, and PostgreSQL may then choose a generic plan; the EXPLAIN uses literal values (a custom plan).",
                    "Client time includes the round trip to PostgreSQL. With Docker Desktop that crosses into a VM, adding latency that a co-located database would not have.",
                    "A sequential scan may run in parallel (max_parallel_workers_per_gather is recorded); parallel workers use more CPU to finish sooner.",
                    "Accounts are chosen uniformly; skewed real data (a few very large accounts) can make a plan that is right on average wrong for the largest keys."));

    @Override
    public ScenarioDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ScenarioResult run(RunSpec spec, RunContext ctx) throws Exception {
        String ddl = DDL.get(spec.variant());
        if (!DDL.containsKey(spec.variant())) {
            throw new IllegalArgumentException("unknown variant " + spec.variant());
        }
        int rows = spec.intParam("rows");
        int accounts = spec.intParam("accounts");
        int windowDays = spec.intParam("windowDays");
        int perTrial = spec.intParam("queriesPerTrial");
        long dataSeed = spec.longParam("dataSeed");
        Instant from = HISTORY_END.minus(windowDays, ChronoUnit.DAYS);

        ScenarioResult.Builder result = ScenarioResult.builder();
        try (LabSession session = LabSession.open(ctx, spec.runId())) {
            Connection c = session.connection();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("postgres", session.serverFacts());
            ctx.info("seeding " + rows + " transactions over " + accounts + " accounts in schema " + session.schema());
            TransactionHistory.Dataset data = TransactionHistory.seed(c, dataSeed, accounts, rows);
            ctx.info(String.format("seeded in %.0f ms (txn %.1f MB)", data.seedMs(), data.txnMb()));
            details.put("dataset", data.toMap());

            Map<String, Object> index = null;
            if (ddl != null) {
                long t0 = System.nanoTime();
                try (Statement st = c.createStatement()) {
                    st.execute(ddl);
                    st.execute("analyze txn");
                }
                double buildMs = (System.nanoTime() - t0) / 1e6;
                String name = ddl.split("\\s+")[2];
                index = new LinkedHashMap<>();
                index.put("name", name);
                index.put("ddl", ddl);
                index.put("buildMs", Math.round(buildMs * 10) / 10.0);
                index.put("sizeMb", Math.round(TransactionHistory.relationMb(c, name) * 100) / 100.0);
                ctx.info(String.format("built %s in %.0f ms (%.2f MB)", name, buildMs, (double) index.get("sizeMb")));
            }
            details.put("index", index);
            details.put("queries", List.of(Map.of("label", "lookup", "sql", QUERY)));

            long probeAccount = 1 + Math.floorMod(dataSeed * 7919, accounts);
            String probeSql = QUERY.replace("account_id = ?", "account_id = " + probeAccount)
                    .replace("created_at >= ?", "created_at >= timestamptz '" + from + "'");
            Probe probe = probe(c, probeAccount, from);
            details.put("probe", Map.of("accountId", probeAccount, "rows", probe.rows(), "checksum", probe.checksum(), "sql", probeSql));

            List<Map<String, Object>> plans = new ArrayList<>();
            int total = spec.warmupTrials() + spec.trials();
            try (PreparedStatement ps = c.prepareStatement(QUERY)) {
                for (int t = 0; t < total; t++) {
                    ctx.checkCancelled();
                    boolean warmup = t < spec.warmupTrials();
                    Random random = new Random(dataSeed * 31 + t);   // the same accounts for every variant: a paired comparison
                    double[] ms = new double[perTrial];
                    long returned = 0;
                    for (int q = 0; q < perTrial; q++) {
                        ps.setLong(1, 1 + random.nextInt(accounts));
                        ps.setTimestamp(2, Timestamp.from(from));
                        long t0 = System.nanoTime();
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                returned++;
                            }
                        }
                        ms[q] = (System.nanoTime() - t0) / 1e6;
                    }
                    Explain.Result ex = Explain.analyze(c, probeSql);
                    if (warmup) {
                        ctx.progress(t + 1, total, "warm-up");
                        continue;
                    }
                    int index0 = t - spec.warmupTrials();
                    Summary s = Summary.of(ms);
                    Map<String, Double> m = new LinkedHashMap<>();
                    m.put("medianQueryMs", round(s.median()));
                    m.put("p95QueryMs", round(s.p95()));
                    m.put("explainExecutionMs", round(ex.executionMs()));
                    m.put("sharedHitBlocks", (double) ex.sharedHit());
                    m.put("sharedReadBlocks", (double) ex.sharedRead());
                    m.put("rowsPerQuery", round((double) returned / perTrial));
                    result.trial(new Trial(index0, m, null, ex.accessPath()));
                    if (index0 == 0 || t == total - 1) {
                        plans.add(ex.toMap("probe lookup, trial " + (index0 + 1)));
                    }
                    ctx.progress(t + 1, total, "measured");
                }
            }
            details.put("plans", plans);
            result.detail("database", details);

            List<Trial> trials = result.trials();
            double[] medians = trials.stream().mapToDouble(x -> x.metrics().get("medianQueryMs")).sorted().toArray();
            result.observation("Access path chosen for the probe lookup: " + trials.get(0).note() + ".");
            result.observation(String.format("Median lookup time across %d trials: %.3f ms (per-trial medians %.3f–%.3f ms), %.1f rows per lookup on average.",
                    trials.size(), Summary.median(medians), medians[0], medians[medians.length - 1], trials.get(0).metrics().get("rowsPerQuery")));
            result.observation(String.format("Probe account %d returned %d rows, checksum %d. Identical checksums across variants mean the index changed how the rows were found, not which rows.",
                    probeAccount, probe.rows(), probe.checksum()));
            if (index != null) {
                result.observation(String.format("Building %s took %.0f ms and it occupies %.2f MB (the table: %.2f MB).",
                        index.get("name"), (double) index.get("buildMs"), (double) index.get("sizeMb"), data.txnMb()));
            }
        }
        return result.build();
    }

    record Probe(long rows, long checksum) {
    }

    /** Rows and an order-sensitive checksum of the probe query's result. */
    static Probe probe(Connection c, long account, Instant from) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(QUERY)) {
            ps.setLong(1, account);
            ps.setTimestamp(2, Timestamp.from(from));
            long rows = 0;
            long sum = 17;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows++;
                    long cents = rs.getBigDecimal(2).movePointRight(2).longValueExact();
                    sum = sum * 31 + rs.getTimestamp(1).getTime() * 7 + cents;
                }
            }
            return new Probe(rows, sum);
        }
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
