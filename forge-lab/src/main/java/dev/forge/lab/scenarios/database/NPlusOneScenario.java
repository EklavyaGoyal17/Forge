package dev.forge.lab.scenarios.database;

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

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Database Studio: the N+1 query problem — loading accounts with their recent transactions one
 * query per account, versus one join, versus one batched {@code = ANY(?)} query.
 */
public final class NPlusOneScenario implements Scenario {

    public static final String ID = "database.n-plus-one";
    private static final String SRC = NPlusOneScenario.class.getName() + "#";

    static final String TXN_INDEX = "create index txn_account_created_idx on txn (account_id, created_at)";

    static final String ACCOUNTS = """
            select id, customer from account
            where region = 'north'
            order by id
            limit ?""";

    /** One account's transactions since a date: what an ORM issues when a lazy collection is touched. */
    static final String TXNS_OF_ONE = """
            select account_id, id, amount, created_at from txn
            where account_id = ? and created_at >= ?
            order by created_at, id""";

    /** A loaded account and its transactions. */
    record Loaded(long accountId, String customer, List<long[]> txns) {
    }

    /** What one strategy did: the object graph plus how many statements it took. */
    record Load(List<Loaded> graph, int queries) {
        long checksum() {
            long sum = 17;
            for (Loaded a : graph) {
                sum = sum * 31 + a.accountId();
                for (long[] t : a.txns()) {
                    sum = sum * 31 + t[0] * 7 + t[1];
                }
            }
            return sum;
        }

        long rows() {
            return graph.stream().mapToLong(a -> a.txns().size()).sum();
        }
    }

    interface Strategy {
        Load load(Connection c, int accounts, Timestamp since) throws SQLException;
    }

    // forge:snippet n-plus-one
    static Load nPlusOne(Connection c, int accounts, Timestamp since) throws SQLException {
        List<Loaded> graph = new ArrayList<>();
        int queries = 1;
        try (PreparedStatement accountsPs = c.prepareStatement(ACCOUNTS);
             PreparedStatement txnsPs = c.prepareStatement(TXNS_OF_ONE)) {
            accountsPs.setInt(1, accounts);
            try (ResultSet rs = accountsPs.executeQuery()) {
                while (rs.next()) {
                    graph.add(new Loaded(rs.getLong(1), rs.getString(2), new ArrayList<>()));
                }
            }
            for (Loaded a : graph) {                       // one more round trip per account
                txnsPs.setLong(1, a.accountId());
                txnsPs.setTimestamp(2, since);
                try (ResultSet rs = txnsPs.executeQuery()) {
                    while (rs.next()) {
                        a.txns().add(txn(rs));
                    }
                }
                queries++;
            }
        }
        return new Load(graph, queries);
    }
    // forge:end

    // forge:snippet join
    static final String JOIN = """
            select a.id, a.customer, t.id, t.amount, t.created_at
            from (select id, customer from account where region = 'north' order by id limit ?) a
            left join txn t on t.account_id = a.id and t.created_at >= ?
            order by a.id, t.created_at, t.id""";

    static Load join(Connection c, int accounts, Timestamp since) throws SQLException {
        Map<Long, Loaded> graph = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(JOIN)) {
            ps.setInt(1, accounts);
            ps.setTimestamp(2, since);
            try (ResultSet rs = ps.executeQuery()) {           // one round trip; account columns repeat per row
                while (rs.next()) {
                    Loaded a = graph.computeIfAbsent(rs.getLong(1), id -> {
                        try {
                            return new Loaded(id, rs.getString(2), new ArrayList<>());
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    });
                    long txnId = rs.getLong(3);
                    if (!rs.wasNull()) {
                        a.txns().add(new long[] {txnId, cents(rs.getBigDecimal(4)), rs.getTimestamp(5).getTime()});
                    }
                }
            }
        }
        return new Load(new ArrayList<>(graph.values()), 1);
    }
    // forge:end

    // forge:snippet batched
    static final String TXNS_OF_MANY = """
            select account_id, id, amount, created_at from txn
            where account_id = any(?) and created_at >= ?
            order by account_id, created_at, id""";

    static Load batched(Connection c, int accounts, Timestamp since) throws SQLException {
        Map<Long, Loaded> graph = new LinkedHashMap<>();
        try (PreparedStatement accountsPs = c.prepareStatement(ACCOUNTS)) {
            accountsPs.setInt(1, accounts);
            try (ResultSet rs = accountsPs.executeQuery()) {
                while (rs.next()) {
                    graph.put(rs.getLong(1), new Loaded(rs.getLong(1), rs.getString(2), new ArrayList<>()));
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(TXNS_OF_MANY)) {
            Array ids = c.createArrayOf("bigint", graph.keySet().toArray());
            ps.setArray(1, ids);                                // all accounts in one round trip
            ps.setTimestamp(2, since);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    graph.get(rs.getLong(1)).txns().add(txn(rs));
                }
            }
        }
        return new Load(new ArrayList<>(graph.values()), 2);
    }
    // forge:end

    private static long[] txn(ResultSet rs) throws SQLException {
        return new long[] {rs.getLong(2), cents(rs.getBigDecimal(3)), rs.getTimestamp(4).getTime()};
    }

    private static long cents(java.math.BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    private static final Map<String, Strategy> STRATEGIES = Map.of(
            "n-plus-one", NPlusOneScenario::nPlusOne,
            "join", NPlusOneScenario::join,
            "batched", NPlusOneScenario::batched);

    private static final ScenarioDescriptor DESCRIPTOR = new ScenarioDescriptor(
            ID, 1, Lab.DATABASE,
            "The N+1 query problem",
            "Load a page of accounts with their recent transactions three ways: one query per account (what lazy ORM collections do), one join, or two queries with = ANY(array). All three have the same index; the difference is round trips.",
            "How much of the N+1 cost is the database doing work, and how much is waiting for N round trips?",
            "Every strategy loads exactly the same accounts and transactions, in the same order, as the single join.",
            List.of(
                    TransactionHistory.dataSeedParameter(),
                    ParameterSpec.integer("rows", "Transactions", 200_000, 10_000, 2_000_000, "rows", "Rows seeded into txn."),
                    ParameterSpec.integer("accounts", "Accounts loaded", 100, 10, 1_000, "accounts",
                            "Accounts per load (the N in N+1); about a quarter of all accounts are in the 'north' region."),
                    ParameterSpec.integer("days", "Transactions since", 30, 1, 730, "days", "Only transactions from the last N days are loaded."),
                    ParameterSpec.integer("loadsPerTrial", "Loads per trial", 10, 3, 100, "loads", "Complete loads per trial, each timed from the client.")),
            List.of(
                    new VariantSpec("n-plus-one", "N+1 (one query per account)", "1 query for the accounts + 1 per account.", true, SRC + "n-plus-one"),
                    new VariantSpec("join", "Single join", "One statement; the account columns repeat on every row.", true, SRC + "join"),
                    new VariantSpec("batched", "Batched (= ANY)", "2 statements regardless of N.", true, SRC + "batched")),
            List.of(
                    new MetricSpec("medianLoadMs", "Median load time", "ms", Direction.LOWER_IS_BETTER,
                            "Median client-measured time to load the whole graph."),
                    new MetricSpec("p95LoadMs", "p95 load time", "ms", Direction.LOWER_IS_BETTER, "95th percentile of the trial's loads."),
                    new MetricSpec("queriesPerLoad", "Statements per load", "queries", Direction.LOWER_IS_BETTER, "Round trips needed for one load."),
                    new MetricSpec("rowsPerLoad", "Transactions per load", "rows", Direction.NEUTRAL, "Transactions in the loaded graph."),
                    new MetricSpec("roundTripMs", "Round trip (select 1)", "ms", Direction.NEUTRAL,
                            "Median time of a trivial query on this connection: the floor every statement pays.")),
            "medianLoadMs",
            new ScenarioDescriptor.TrialPolicy(5, 30, 1, 5),
            300,
            """
            Every statement costs at least one network round trip plus parsing, planning and executing — even when \
            it returns three rows. Loading N accounts' transactions one account at a time pays that fixed cost N + 1 \
            times; the database work itself (finding a few rows through an index) is often the smaller part.

            A join does it in one statement but repeats the account columns on every transaction row; a batched \
            query (= ANY(array)) keeps two statements no matter how large N is. Which is faster depends on row width, \
            N and — above all — the round-trip time, which is measured here so you can extrapolate: on a network \
            with a 1 ms round trip, 100 extra statements cost at least 100 ms.""",
            List.of(
                    "Round trips here are local (Docker on this machine, possibly through a VM). Across a real network each extra statement costs more, so N+1 gets relatively worse.",
                    "The JDBC driver switches to server-side prepared statements after five executions, which removes per-statement planning for the N+1 loop — ORMs and drivers differ.",
                    "All strategies use the same (account_id, created_at) index; without it each of the N queries would scan txn.",
                    "The join transfers the account columns once per transaction row; with wide parent rows that duplication can outweigh the saved round trips.",
                    "Very large ANY arrays have their own planning cost; batch sizes are usually capped in practice."));

    @Override
    public ScenarioDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ScenarioResult run(RunSpec spec, RunContext ctx) throws Exception {
        Strategy strategy = STRATEGIES.get(spec.variant());
        if (strategy == null) {
            throw new IllegalArgumentException("unknown variant " + spec.variant());
        }
        int rows = spec.intParam("rows");
        int accounts = spec.intParam("accounts");
        int days = spec.intParam("days");
        int loads = spec.intParam("loadsPerTrial");
        Timestamp since = Timestamp.from(IndexLookupScenario.HISTORY_END.minus(days, ChronoUnit.DAYS));

        ScenarioResult.Builder result = ScenarioResult.builder();
        try (LabSession session = LabSession.open(ctx, spec.runId())) {
            Connection c = session.connection();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("postgres", session.serverFacts());
            int accountRows = Math.max(4 * accounts, rows / 40);
            TransactionHistory.Dataset data = TransactionHistory.seed(c, spec.longParam("dataSeed"), accountRows, rows);
            details.put("dataset", data.toMap());
            try (Statement st = c.createStatement()) {
                st.execute(TXN_INDEX);
                st.execute("analyze txn");
            }
            details.put("index", Map.of("name", "txn_account_created_idx", "ddl", TXN_INDEX));
            details.put("queries", switch (spec.variant()) {
                case "n-plus-one" -> List.of(Map.of("label", "accounts (once)", "sql", ACCOUNTS), Map.of("label", "transactions (per account)", "sql", TXNS_OF_ONE));
                case "join" -> List.of(Map.of("label", "join", "sql", JOIN));
                default -> List.of(Map.of("label", "accounts", "sql", ACCOUNTS), Map.of("label", "transactions of all accounts", "sql", TXNS_OF_MANY));
            });

            int total = spec.warmupTrials() + spec.trials();
            long referenceChecksum = 0;
            for (int t = 0; t < total; t++) {
                ctx.checkCancelled();
                Load reference = join(c, accounts, since);            // untimed: the invariant's reference
                referenceChecksum = reference.checksum();
                double[] ms = new double[loads];
                Load last = null;
                boolean same = true;
                for (int l = 0; l < loads; l++) {
                    long t0 = System.nanoTime();
                    last = strategy.load(c, accounts, since);
                    ms[l] = (System.nanoTime() - t0) / 1e6;
                    same &= last.checksum() == referenceChecksum;
                }
                double rtt = roundTripMs(c);
                if (t < spec.warmupTrials()) {
                    ctx.progress(t + 1, total, "warm-up");
                    continue;
                }
                Summary s = Summary.of(ms);
                Map<String, Double> m = new LinkedHashMap<>();
                m.put("medianLoadMs", round(s.median()));
                m.put("p95LoadMs", round(s.p95()));
                m.put("queriesPerLoad", (double) last.queries());
                m.put("rowsPerLoad", (double) last.rows());
                m.put("roundTripMs", round(rtt));
                result.trial(new Trial(t - spec.warmupTrials(), m, same,
                        same ? null : "loaded graph differs from the join's"));
                ctx.progress(t + 1, total, "measured");
            }
            details.put("graph", Map.of("accounts", accounts, "referenceChecksum", referenceChecksum));
            result.detail("database", details);

            List<Trial> trials = result.trials();
            result.invariant(InvariantSummary.of(DESCRIPTOR.invariant(), trials));
            double[] medians = trials.stream().mapToDouble(x -> x.metrics().get("medianLoadMs")).sorted().toArray();
            double[] rtts = trials.stream().mapToDouble(x -> x.metrics().get("roundTripMs")).sorted().toArray();
            Trial first = trials.get(0);
            double queries = first.metrics().get("queriesPerLoad");
            double rttMedian = IndexLookupScenario.median(rtts);
            double medianLoad = IndexLookupScenario.median(medians);
            result.observation(String.format("%.0f statements per load of %d accounts and %.0f transactions; median load %.3f ms across %d trials (per-trial medians %.3f–%.3f ms).",
                    queries, accounts, first.metrics().get("rowsPerLoad"), medianLoad, trials.size(), medians[0], medians[medians.length - 1]));
            result.observation(String.format("Median round trip of a trivial statement: %.3f ms. %.0f statements × %.3f ms = %.3f ms, i.e. %.0f%% of the median load time is the floor of round trips alone.",
                    rttMedian, queries, rttMedian, queries * rttMedian, Math.min(100, 100 * queries * rttMedian / medianLoad)));
            result.observation("The loaded graph matched the single join in " + trials.stream().filter(x -> Boolean.TRUE.equals(x.invariantHeld())).count()
                    + " of " + trials.size() + " trials.");
        }
        return result.build();
    }

    static double roundTripMs(Connection c) throws SQLException {
        double[] ms = new double[20];
        try (PreparedStatement ps = c.prepareStatement("select 1")) {
            for (int i = 0; i < ms.length; i++) {
                long t0 = System.nanoTime();
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                }
                ms[i] = (System.nanoTime() - t0) / 1e6;
            }
        }
        return Summary.of(ms).median();
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
