package dev.forge.lab.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seeds the synthetic transaction history every Database Studio scenario works on.
 *
 * <ul>
 *   <li>{@code account(id, customer, region, opened_at, balance)} — {@code accounts} rows;</li>
 *   <li>{@code txn(id, account_id → account, amount, kind, merchant, created_at, status)} —
 *       {@code rows} rows, account chosen uniformly, {@code created_at} increasing with {@code id}
 *       over two years plus up to one hour of jitter.</li>
 * </ul>
 * Values come from PostgreSQL's {@code random()} after {@code setseed()} derived from the run's
 * seed, so the same seed produces the same data on the same PostgreSQL version. Only primary keys
 * exist after seeding (note: PostgreSQL does not index foreign-key columns by itself). Both tables
 * are vacuumed and analysed, so planner statistics and the visibility map are current.
 */
public final class TransactionHistory {

    private TransactionHistory() {
    }

    public record Dataset(long accounts, long rows, double seedMs, double accountMb, double txnMb) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accounts", accounts);
            m.put("txnRows", rows);
            m.put("seedMs", Math.round(seedMs * 10) / 10.0);
            m.put("accountTableMb", Math.round(accountMb * 100) / 100.0);
            m.put("txnTableMb", Math.round(txnMb * 100) / 100.0);
            return m;
        }
    }

    static final String CREATE = """
            create table account (
                id        bigint primary key,
                customer  text not null,
                region    text not null,
                opened_at timestamptz not null,
                balance   numeric(14, 2) not null
            );
            create table txn (
                id         bigint primary key,
                account_id bigint not null references account (id),
                amount     numeric(12, 2) not null,
                kind       text not null,
                merchant   text not null,
                created_at timestamptz not null,
                status     text not null
            )""";

    static final String ACCOUNTS = """
            insert into account
            select g, 'customer-' || g,
                   (array['north', 'south', 'east', 'west'])[1 + floor(random() * 4)::int],
                   timestamptz '2023-01-01' + random() * interval '365 days',
                   0
            from generate_series(1, ?) g""";

    static final String TRANSACTIONS = """
            insert into txn
            select g,
                   1 + floor(random() * ?)::bigint,
                   round((random() * 500)::numeric, 2) * (case when random() < 0.1 then -1 else 1 end),
                   (array['card', 'transfer', 'fee', 'refund'])[1 + floor(random() * 4)::int],
                   'merchant-' || floor(random() * 2000)::int,
                   timestamptz '2024-01-01' + (g::double precision / ?) * interval '730 days'
                       + random() * interval '1 hour',
                   case when random() < 0.02 then 'declined' else 'settled' end
            from generate_series(1, ?) g""";

    public static Dataset seed(Connection c, long seed, int accounts, int rows) throws SQLException {
        long t0 = System.nanoTime();
        try (Statement st = c.createStatement()) {
            st.execute(CREATE);
        }
        try (PreparedStatement ps = c.prepareStatement("select setseed(?)")) {
            ps.setDouble(1, seedFraction(seed));
            ps.execute();
        }
        try (PreparedStatement ps = c.prepareStatement(ACCOUNTS)) {
            ps.setInt(1, accounts);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(TRANSACTIONS)) {
            ps.setInt(1, accounts);
            ps.setDouble(2, rows);
            ps.setInt(3, rows);
            ps.executeUpdate();
        }
        try (Statement st = c.createStatement()) {
            st.execute("vacuum analyze account");
            st.execute("vacuum analyze txn");
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        return new Dataset(accounts, rows, ms, relationMb(c, "account"), relationMb(c, "txn"));
    }

    /** Maps any long seed into setseed's accepted range [-1, 1]. */
    static double seedFraction(long seed) {
        return ((seed % 1_000_000_007L) / 1_000_000_007.0);
    }

    public static double relationMb(Connection c, String relation) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select pg_relation_size(?::regclass)")) {
            ps.setString(1, relation);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) / (1024.0 * 1024.0);
            }
        }
    }
}
