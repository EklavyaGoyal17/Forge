package dev.forge.lab.scenarios.database;

import dev.forge.lab.database.LabSession;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Database Studio: the lost update again — this time in SQL, with isolation levels and row locks
 * instead of Java monitors. Concurrent connections deposit 1 into a few hot wallet rows.
 */
public final class SqlLostUpdateScenario implements Scenario {

    public static final String ID = "database.lost-update";
    private static final String SRC = SqlLostUpdateScenario.class.getName() + "#";
    /** Bound on retries of one deposit after serialization failures. */
    static final int MAX_RETRIES = 200;

    /** One deposit of 1 into wallet {@code id}; returns the number of retries it needed. */
    interface Deposit {
        int apply(Connection c, int id) throws SQLException;
    }

    // forge:snippet read-modify-write
    static int readModifyWrite(Connection c, int id) throws SQLException {
        c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);   // PostgreSQL's default
        c.setAutoCommit(false);
        try (PreparedStatement read = c.prepareStatement("select balance from wallet where id = ?");
             PreparedStatement write = c.prepareStatement("update wallet set balance = ? where id = ?")) {
            read.setInt(1, id);
            long balance;
            try (ResultSet rs = read.executeQuery()) {
                rs.next();
                balance = rs.getLong(1);                 // another transaction may commit a new balance now…
            }
            write.setLong(1, balance + 1);               // …and this overwrites it
            write.setInt(2, id);
            write.executeUpdate();
            c.commit();
        }
        return 0;
    }
    // forge:end

    // forge:snippet select-for-update
    static int selectForUpdate(Connection c, int id) throws SQLException {
        c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        c.setAutoCommit(false);
        try (PreparedStatement read = c.prepareStatement("select balance from wallet where id = ? for update");
             PreparedStatement write = c.prepareStatement("update wallet set balance = ? where id = ?")) {
            read.setInt(1, id);
            long balance;
            try (ResultSet rs = read.executeQuery()) {   // row lock: concurrent depositors wait here
                rs.next();
                balance = rs.getLong(1);
            }
            write.setLong(1, balance + 1);
            write.setInt(2, id);
            write.executeUpdate();
            c.commit();                                  // releases the lock
        }
        return 0;
    }
    // forge:end

    // forge:snippet atomic-update
    static int atomicUpdate(Connection c, int id) throws SQLException {
        c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        c.setAutoCommit(true);
        try (PreparedStatement ps = c.prepareStatement("update wallet set balance = balance + 1 where id = ?")) {
            ps.setInt(1, id);                            // read and write in one statement; a concurrent
            ps.executeUpdate();                          // update waits, then re-reads the committed row
        }
        return 0;
    }
    // forge:end

    // forge:snippet repeatable-read-retry
    static int repeatableReadRetry(Connection c, int id) throws SQLException {
        return withRetry(c, Connection.TRANSACTION_REPEATABLE_READ, id);
    }
    // forge:end

    // forge:snippet serializable-retry
    static int serializableRetry(Connection c, int id) throws SQLException {
        return withRetry(c, Connection.TRANSACTION_SERIALIZABLE, id);
    }

    /** Read-modify-write under a snapshot; PostgreSQL aborts the loser with SQLSTATE 40001, so retry. */
    static int withRetry(Connection c, int isolation, int id) throws SQLException {
        c.setAutoCommit(false);
        c.setTransactionIsolation(isolation);
        for (int attempt = 0; ; attempt++) {
            try (PreparedStatement read = c.prepareStatement("select balance from wallet where id = ?");
                 PreparedStatement write = c.prepareStatement("update wallet set balance = ? where id = ?")) {
                read.setInt(1, id);
                long balance;
                try (ResultSet rs = read.executeQuery()) {
                    rs.next();
                    balance = rs.getLong(1);
                }
                write.setLong(1, balance + 1);
                write.setInt(2, id);
                write.executeUpdate();
                c.commit();
                return attempt;
            } catch (SQLException e) {
                c.rollback();
                if (!"40001".equals(e.getSQLState()) || attempt >= MAX_RETRIES) {
                    throw e;
                }
            }
        }
    }
    // forge:end

    private static final Map<String, Deposit> DEPOSITS = Map.of(
            "read-modify-write", SqlLostUpdateScenario::readModifyWrite,
            "select-for-update", SqlLostUpdateScenario::selectForUpdate,
            "atomic-update", SqlLostUpdateScenario::atomicUpdate,
            "repeatable-read-retry", SqlLostUpdateScenario::repeatableReadRetry,
            "serializable-retry", SqlLostUpdateScenario::serializableRetry);

    private static final ScenarioDescriptor DESCRIPTOR = new ScenarioDescriptor(
            ID, 1, Lab.DATABASE,
            "Lost updates in SQL: isolation levels and row locks",
            "Concurrent connections each deposit 1 into a few hot wallet rows. A read-then-write under READ COMMITTED loses deposits; a row lock, an atomic UPDATE, or a snapshot isolation level with retries does not — at different costs.",
            "Which SQL patterns keep every concurrent deposit, and what do locking and serialization failures cost?",
            "After W workers each deposit K times, the wallets' total is exactly W × K.",
            List.of(
                    ParameterSpec.integer("workers", "Workers", 4, 2, 12, "connections", "Concurrent depositing connections."),
                    ParameterSpec.integer("depositsPerWorker", "Deposits per worker", 200, 10, 2_000, "deposits", "Deposits each worker makes per trial."),
                    ParameterSpec.integer("hotRows", "Wallet rows", 1, 1, 10, "rows", "Rows the deposits are spread over; fewer rows = more contention.")),
            List.of(
                    new VariantSpec("read-modify-write", "SELECT then UPDATE (READ COMMITTED)", "Reads the balance, computes, writes it back: two statements, no lock.", false, SRC + "read-modify-write"),
                    new VariantSpec("select-for-update", "SELECT … FOR UPDATE", "The read takes a row lock that blocks other depositors until commit.", true, SRC + "select-for-update"),
                    new VariantSpec("atomic-update", "UPDATE … SET balance = balance + 1", "One statement; PostgreSQL re-checks the row after waiting for a concurrent update.", true, SRC + "atomic-update"),
                    new VariantSpec("repeatable-read-retry", "REPEATABLE READ + retry", "Snapshot isolation: a conflicting update aborts with 40001 and is retried.", true, SRC + "repeatable-read-retry"),
                    new VariantSpec("serializable-retry", "SERIALIZABLE + retry", "Serializable snapshot isolation: aborts with 40001 and retries.", true, SRC + "serializable-retry")),
            List.of(
                    new MetricSpec("throughputPerSec", "Throughput", "deposits/s", Direction.HIGHER_IS_BETTER, "Committed deposits per second of wall-clock time."),
                    new MetricSpec("lostDeposits", "Lost deposits", "deposits", Direction.LOWER_IS_BETTER, "Expected total − actual total."),
                    new MetricSpec("retries", "Retries (40001)", "retries", Direction.LOWER_IS_BETTER, "Transactions aborted by a serialization failure and retried."),
                    new MetricSpec("depositP50Ms", "Deposit time p50", "ms", Direction.LOWER_IS_BETTER, "Per deposit, including retries and lock waits."),
                    new MetricSpec("depositP99Ms", "Deposit time p99", "ms", Direction.LOWER_IS_BETTER, "Per deposit, including retries and lock waits."),
                    new MetricSpec("durationMs", "Trial duration", "ms", Direction.LOWER_IS_BETTER, "Start gate to last worker finished.")),
            "throughputPerSec",
            new ScenarioDescriptor.TrialPolicy(5, 30, 1, 5),
            300,
            """
            Under READ COMMITTED each statement sees the latest committed data. "Read the balance, then write \
            balance + 1" is two statements: if another transaction commits in between, the second write is based on a \
            stale read and silently replaces the other deposit. Nothing errors — the money just disappears.

            SELECT … FOR UPDATE locks the row at the read, so depositors queue up. A single UPDATE … SET balance = \
            balance + 1 needs no explicit lock: if a concurrent transaction changed the row, PostgreSQL waits for it to \
            commit and re-evaluates the update against the new version. Under REPEATABLE READ and SERIALIZABLE the \
            stale read is detected instead: the second writer gets "could not serialize access" (SQLSTATE 40001) and \
            must retry the whole transaction — correctness moves into the application's retry loop.""",
            List.of(
                    "The racy variant's loss rate depends on timing (core count, round-trip latency, Docker's VM boundary): zero lost deposits in some trials does not make it correct.",
                    "Each worker uses its own connection; all of them run in this runner JVM against a PostgreSQL on the same machine, so CPU is shared.",
                    "Deposit times include lock waits and retries; the role's lock_timeout (10 s) would abort a deposit that waited longer.",
                    "Retries are capped at " + MAX_RETRIES + " per deposit; exhausting them fails the run rather than hiding the failure.",
                    "SERIALIZABLE in PostgreSQL also detects anomalies beyond lost updates (write skew) that this workload does not exercise."));

    @Override
    public ScenarioDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ScenarioResult run(RunSpec spec, RunContext ctx) throws Exception {
        Deposit deposit = DEPOSITS.get(spec.variant());
        if (deposit == null) {
            throw new IllegalArgumentException("unknown variant " + spec.variant());
        }
        int workers = spec.intParam("workers");
        int perWorker = spec.intParam("depositsPerWorker");
        int hotRows = spec.intParam("hotRows");

        ScenarioResult.Builder result = ScenarioResult.builder();
        try (LabSession session = LabSession.open(ctx, spec.runId())) {
            Connection admin = session.connection();
            try (Statement st = admin.createStatement()) {
                st.execute("create table wallet (id int primary key, balance bigint not null)");
            }
            List<Connection> connections = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                connections.add(session.newConnection());
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("postgres", session.serverFacts());

            int total = spec.warmupTrials() + spec.trials();
            for (int t = 0; t < total; t++) {
                ctx.checkCancelled();
                try (Statement st = admin.createStatement()) {
                    st.execute("truncate wallet");
                    st.execute("insert into wallet select g, 0 from generate_series(1, " + hotRows + ") g");
                }
                Outcome o = trial(connections, deposit, perWorker, hotRows, spec.seed() * 31 + t, ctx);
                long actual;
                try (Statement st = admin.createStatement(); ResultSet rs = st.executeQuery("select coalesce(sum(balance), 0) from wallet")) {
                    rs.next();
                    actual = rs.getLong(1);
                }
                if (t < spec.warmupTrials()) {
                    ctx.progress(t + 1, total, "warm-up");
                    continue;
                }
                long expected = (long) workers * perWorker;
                long lost = expected - actual;
                Summary latency = Summary.of(o.latenciesMs());
                Map<String, Double> m = new LinkedHashMap<>();
                m.put("throughputPerSec", Math.round(expected / (o.elapsedNanos() / 1e9) * 10) / 10.0);
                m.put("lostDeposits", (double) lost);
                m.put("retries", (double) o.retries());
                m.put("depositP50Ms", round(latency.median()));
                m.put("depositP99Ms", round(latency.p99()));
                m.put("durationMs", round(o.elapsedNanos() / 1e6));
                result.trial(new Trial(t - spec.warmupTrials(), m, lost == 0, lost == 0 ? null : lost + " deposits lost"));
                ctx.progress(t + 1, total, "measured");
            }
            details.put("statements", List.of(Map.of("label", "schema", "sql", "create table wallet (id int primary key, balance bigint not null)")));
            result.detail("database", details);
        }

        List<Trial> trials = result.trials();
        InvariantSummary inv = InvariantSummary.of(DESCRIPTOR.invariant(), trials);
        result.invariant(inv);
        result.observation("Invariant violated in " + inv.violations() + " of " + inv.checked() + " measured trials.");
        double[] lost = trials.stream().mapToDouble(x -> x.metrics().get("lostDeposits")).sorted().toArray();
        double[] retries = trials.stream().mapToDouble(x -> x.metrics().get("retries")).sorted().toArray();
        double[] tput = trials.stream().mapToDouble(x -> x.metrics().get("throughputPerSec")).sorted().toArray();
        if (inv.violations() > 0) {
            result.observation(String.format("Lost deposits per trial ranged from %.0f to %.0f of %d.", lost[0], lost[lost.length - 1], workers * perWorker));
        } else if (Boolean.FALSE.equals(DESCRIPTOR.variant(spec.variant()).map(VariantSpec::expectedCorrect).orElse(null))) {
            result.observation("No deposit was lost in these trials, although this pattern is racy: the interleaving that loses one did not happen here — that does not mean it cannot.");
        }
        result.observation(String.format("Median throughput %.1f deposits/s; serialization-failure retries per trial %.0f–%.0f.",
                Summary.median(tput), retries[0], retries[retries.length - 1]));
        return result.build();
    }

    record Outcome(long elapsedNanos, long retries, double[] latenciesMs) {
    }

    private static Outcome trial(List<Connection> connections, Deposit deposit, int perWorker, int hotRows, long seed,
                                 RunContext ctx) throws Exception {
        int workers = connections.size();
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicLong retries = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        double[] latencies = new double[workers * perWorker];
        Thread[] threads = new Thread[workers];
        for (int w = 0; w < workers; w++) {
            int worker = w;
            Connection c = connections.get(w);
            threads[w] = new Thread(() -> {
                Random random = new Random(seed * 1_000 + worker);
                ready.countDown();
                try {
                    go.await();
                    for (int k = 0; k < perWorker; k++) {
                        if (System.nanoTime() - ctx.deadlineNanos() > 0 || failure.get() != null) {
                            return;
                        }
                        long t0 = System.nanoTime();
                        retries.addAndGet(deposit.apply(c, 1 + random.nextInt(hotRows)));
                        latencies[worker * perWorker + k] = (System.nanoTime() - t0) / 1e6;
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }, "depositor-" + w);
            threads[w].setDaemon(true);
            threads[w].start();
        }
        ready.await();
        long begin = System.nanoTime();
        go.countDown();
        for (Thread th : threads) {
            long remaining = ctx.deadlineNanos() - System.nanoTime();
            th.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            if (th.isAlive()) {
                throw new RunContext.CancelledException("depositors did not finish before the run deadline");
            }
        }
        long elapsed = System.nanoTime() - begin;
        if (failure.get() != null) {
            throw new IllegalStateException("a depositor failed: " + failure.get(), failure.get());
        }
        ctx.checkCancelled();
        return new Outcome(elapsed, retries.get(), latencies);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
