package dev.forge.server.lab;

import dev.forge.lab.scenario.LabDatabase;
import dev.forge.server.config.LabDatabaseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provisions the disposable lab database that Database Studio scenarios run against.
 *
 * <p>Using the platform connection (which must be allowed to create roles and databases — the
 * compose PostgreSQL user is), it creates or updates:
 * <ul>
 *   <li>a <b>restricted role</b> ({@code LOGIN}, no superuser, no CREATEDB/CREATEROLE/REPLICATION,
 *       a connection limit, and role-level statement, lock and idle-in-transaction timeouts) whose
 *       password is regenerated on every server start;</li>
 *   <li>a <b>separate database</b> owned by that role, so scenarios can create their per-run schemas;</li>
 *   <li>{@code REVOKE CONNECT ON DATABASE <platform> FROM PUBLIC}, so the lab role cannot even
 *       connect to the platform database. This is only done when the platform user owns its
 *       database (otherwise revoking could lock the platform out); {@link #status()} reports
 *       whether isolation is enforced.</li>
 * </ul>
 * Each run's data lives in its own schema ({@link LabDatabase#schemaFor}); the scenario drops it,
 * and {@link #dropRunSchema} drops it again after the run in case the runner was killed.
 */
@Service
public class LabDatabaseService {

    private static final Logger log = LoggerFactory.getLogger(LabDatabaseService.class);
    private static final Pattern PG_URL = Pattern.compile("^(jdbc:postgresql://[^/?]+/)([^?]*)(\\?.*)?$");

    private final DataSource platform;
    private final LabDatabaseProperties props;
    private final String password;
    private volatile LabDatabase credentials;
    private volatile Boolean platformIsolated;

    public LabDatabaseService(DataSource platform, LabDatabaseProperties props) {
        this.platform = platform;
        this.props = props;
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        this.password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Creates or updates the role and database once per server process; returns runner credentials. */
    public synchronized LabDatabase ensure() {
        if (!props.enabled()) {
            throw new IllegalStateException("the lab database is disabled (forge.lab-database.enabled=false)");
        }
        if (credentials != null) {
            return credentials;
        }
        String role = props.role();
        String db = props.database();
        String url;
        try (Connection c = platform.getConnection()) {
            c.setAutoCommit(true);
            String platformDb = c.getCatalog();
            if (db.equals(platformDb)) {
                throw new IllegalStateException("the lab database must not be the platform database (" + db + ")");
            }
            url = replaceDatabase(c.getMetaData().getURL(), db);
            try (Statement st = c.createStatement()) {
                boolean roleExists = exists(c, "select 1 from pg_roles where rolname = ?", role);
                // identifiers are validated by LabDatabaseProperties; the password is base64url (no quotes)
                st.execute((roleExists ? "alter role " : "create role ") + role
                        + " login nosuperuser nocreatedb nocreaterole noreplication nobypassrls noinherit"
                        + " connection limit " + props.connectionLimit() + " password '" + password + "'");
                st.execute("alter role " + role + " set statement_timeout = '" + props.statementTimeoutSeconds() + "s'");
                st.execute("alter role " + role + " set lock_timeout = '" + props.lockTimeoutSeconds() + "s'");
                st.execute("alter role " + role + " set idle_in_transaction_session_timeout = '"
                        + Math.max(props.statementTimeoutSeconds(), 30) + "s'");
                if (!exists(c, "select 1 from pg_database where datname = ?", db)) {
                    st.execute("create database " + db + " owner " + role);
                }
                st.execute("revoke connect, temporary on database " + db + " from public");
                st.execute("grant connect, temporary on database " + db + " to " + role);
                boolean ownsPlatform = exists(c,
                        "select 1 from pg_database where datname = current_database() and pg_get_userbyid(datdba) = current_user");
                if (ownsPlatform) {
                    st.execute("revoke connect, temporary on database " + quoteIdent(platformDb) + " from public");
                }
                platformIsolated = !exists(c, "select 1 where has_database_privilege(?, current_database(), 'CONNECT')", role);
                if (!platformIsolated) {
                    log.warn("lab role {} can still connect to the platform database {}; isolation is not enforced", role, platformDb);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not provision the lab database: " + e.getMessage(), e);
        }
        LabDatabase lab = new LabDatabase(url, role, password);
        int swept = sweep(lab);
        if (swept > 0) {
            log.info("dropped {} leftover run schema(s) from the lab database", swept);
        }
        credentials = lab;
        log.info("lab database ready: {} as role {} (platform isolation enforced: {})", db, role, platformIsolated);
        return lab;
    }

    /** Drops a run's schema if it is still there (the scenario normally drops it itself). */
    public void dropRunSchema(UUID runId) {
        LabDatabase lab = credentials;
        if (lab == null) {
            return;
        }
        try (Connection c = DriverManager.getConnection(lab.jdbcUrl(), lab.user(), lab.password());
             Statement st = c.createStatement()) {
            st.execute("drop schema if exists " + LabDatabase.schemaFor(runId.toString()) + " cascade");
        } catch (SQLException e) {
            throw new IllegalStateException("could not drop the run schema: " + e.getMessage(), e);
        }
    }

    /** For the system view and run environments; never contains the password. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.enabled());
        m.put("database", props.database());
        m.put("role", props.role());
        m.put("provisioned", credentials != null);
        m.put("platformIsolated", platformIsolated);
        m.put("statementTimeoutSeconds", props.statementTimeoutSeconds());
        m.put("lockTimeoutSeconds", props.lockTimeoutSeconds());
        m.put("connectionLimit", props.connectionLimit());
        return m;
    }

    private static int sweep(LabDatabase lab) {
        List<String> schemas = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(lab.jdbcUrl(), lab.user(), lab.password())) {
            try (PreparedStatement ps = c.prepareStatement(
                    "select nspname from pg_namespace where nspname like ? and pg_get_userbyid(nspowner) = current_user")) {
                ps.setString(1, LabDatabase.SCHEMA_PREFIX.replace("_", "\\_") + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        schemas.add(rs.getString(1));
                    }
                }
            }
            try (Statement st = c.createStatement()) {
                for (String s : schemas) {
                    st.execute("drop schema if exists " + quoteIdent(s) + " cascade");
                }
            }
        } catch (SQLException e) {
            log.warn("could not sweep leftover run schemas: {}", e.getMessage());
            return 0;
        }
        return schemas.size();
    }

    private static boolean exists(Connection c, String sql, String... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    static String replaceDatabase(String jdbcUrl, String database) {
        Matcher m = PG_URL.matcher(jdbcUrl);
        if (!m.matches()) {
            throw new IllegalStateException("not a PostgreSQL JDBC URL: " + jdbcUrl);
        }
        return m.group(1) + database + (m.group(3) == null ? "" : m.group(3));
    }

    static String quoteIdent(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
