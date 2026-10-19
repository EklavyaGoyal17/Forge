package dev.forge.lab.database;

import dev.forge.lab.scenario.LabDatabase;
import dev.forge.lab.scenario.RunContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * A run's private corner of the lab database: one schema named after the run, created empty at
 * the start and dropped (with everything in it) at the end. Every connection it hands out has its
 * {@code search_path} set to that schema, so scenario SQL uses unqualified table names.
 */
public final class LabSession implements AutoCloseable {

    private final LabDatabase db;
    private final String schema;
    private final String runId;
    private final Connection main;
    private final List<Connection> extra = new ArrayList<>();

    private LabSession(LabDatabase db, String runId) throws SQLException {
        this.db = db;
        this.runId = runId;
        this.schema = LabDatabase.schemaFor(runId);
        this.main = connect();
        try (Statement st = main.createStatement()) {
            st.execute("drop schema if exists " + schema + " cascade");
            st.execute("create schema " + schema);
            st.execute("set search_path to " + schema);
        }
    }

    public static LabSession open(RunContext ctx, String runId) throws SQLException {
        LabDatabase db = ctx.labDatabase().orElseThrow(() -> new IllegalStateException(
                "this scenario needs the lab database; it is only available to runs started by the FORGE server"));
        return new LabSession(db, runId);
    }

    /** The session's main connection (auto-commit). */
    public Connection connection() {
        return main;
    }

    /** An additional connection into the same schema, closed with the session (for concurrent workers). */
    public synchronized Connection newConnection() throws SQLException {
        Connection c = connect();
        try (Statement st = c.createStatement()) {
            st.execute("set search_path to " + schema);
        }
        extra.add(c);
        return c;
    }

    public String schema() {
        return schema;
    }

    /** Server version and the planner/memory settings that most influence the experiments. */
    public Map<String, Object> serverFacts() throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String setting : List.of("server_version", "shared_buffers", "work_mem", "effective_cache_size",
                "random_page_cost", "seq_page_cost", "jit", "max_parallel_workers_per_gather", "default_transaction_isolation")) {
            try (Statement st = main.createStatement(); ResultSet rs = st.executeQuery("show " + setting)) {
                rs.next();
                m.put(setting, rs.getString(1));
            }
        }
        return m;
    }

    private Connection connect() throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", db.user());
        p.setProperty("password", db.password());
        p.setProperty("ApplicationName", "forge-run-" + runId);
        Connection c = DriverManager.getConnection(db.jdbcUrl(), p);
        c.setAutoCommit(true);
        return c;
    }

    @Override
    public synchronized void close() throws SQLException {
        for (Connection c : extra) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // the session is ending; the schema drop below is what matters
            }
        }
        extra.clear();
        try (Statement st = main.createStatement()) {
            st.execute("drop schema if exists " + schema + " cascade");
        } finally {
            main.close();
        }
    }
}
