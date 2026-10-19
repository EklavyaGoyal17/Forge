package dev.forge.server.lab;

import dev.forge.lab.scenario.LabDatabase;
import dev.forge.server.config.LabDatabaseProperties;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The isolation claims of the lab database, checked against a real PostgreSQL. */
@Testcontainers(disabledWithoutDocker = true)
class LabDatabaseServiceTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private static final LabDatabaseProperties PROPS = new LabDatabaseProperties(true, "forge_lab", "forge_lab_runner", 8, 60, 10);

    private static LabDatabaseService service() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return new LabDatabaseService(ds, PROPS);
    }

    private static Connection connect(LabDatabase lab) throws SQLException {
        return DriverManager.getConnection(lab.jdbcUrl(), lab.user(), lab.password());
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void labRoleWorksInItsOwnDatabaseOnly() throws Exception {
        LabDatabaseService service = service();
        LabDatabase lab = service.ensure();
        assertThat(lab.jdbcUrl()).contains("/forge_lab");
        assertThat(lab.toString()).doesNotContain(lab.password());
        assertThat(service.status()).containsEntry("provisioned", true).containsEntry("platformIsolated", true);

        try (Connection c = connect(lab); Statement st = c.createStatement()) {
            assertThat(scalar(c, "select current_database()")).isEqualTo("forge_lab");
            assertThat(scalar(c, "show statement_timeout")).isEqualTo("1min");
            assertThat(scalar(c, "show lock_timeout")).isEqualTo("10s");
            st.execute("create schema run_probe");
            st.execute("create table run_probe.t (id int)");
            st.execute("drop schema run_probe cascade");
            assertThatThrownBy(() -> st.execute("create database escape_hatch")).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> st.execute("create role escape_hatch")).hasMessageContaining("permission denied");
        }

        // the platform database is out of reach for the lab role
        String platformUrl = postgres.getJdbcUrl();
        assertThatThrownBy(() -> DriverManager.getConnection(platformUrl, lab.user(), lab.password()).close())
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied for database");
    }

    @Test
    void runSchemasAreDroppedAfterARunAndSweptOnRestart() throws Exception {
        LabDatabaseService service = service();
        LabDatabase lab = service.ensure();
        UUID finished = UUID.randomUUID();
        UUID orphan = UUID.randomUUID();
        try (Connection c = connect(lab); Statement st = c.createStatement()) {
            st.execute("create schema " + LabDatabase.schemaFor(finished.toString()));
            st.execute("create schema " + LabDatabase.schemaFor(orphan.toString()));
            st.execute("create schema keep_me");
        }
        service.dropRunSchema(finished);

        // a new server process: new password, leftover run schemas swept, other schemas untouched
        LabDatabaseService restarted = service();
        LabDatabase lab2 = restarted.ensure();
        assertThat(lab2.password()).isNotEqualTo(lab.password());
        assertThatThrownBy(() -> connect(lab).close()).hasMessageContaining("password authentication failed");
        try (Connection c = connect(lab2)) {
            assertThat(scalar(c, "select count(*) from pg_namespace where nspname like 'run\\_%'")).isEqualTo("0");
            assertThat(scalar(c, "select count(*) from pg_namespace where nspname = 'keep_me'")).isEqualTo("1");
        }
    }

    @Test
    void jdbcUrlKeepsHostAndParameters() {
        assertThat(LabDatabaseService.replaceDatabase("jdbc:postgresql://127.0.0.1:5442/forge", "forge_lab"))
                .isEqualTo("jdbc:postgresql://127.0.0.1:5442/forge_lab");
        assertThat(LabDatabaseService.replaceDatabase("jdbc:postgresql://localhost:32768/test?loggerLevel=OFF", "forge_lab"))
                .isEqualTo("jdbc:postgresql://localhost:32768/forge_lab?loggerLevel=OFF");
        assertThatThrownBy(() -> LabDatabaseService.replaceDatabase("jdbc:mysql://x/y", "z")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void schemaNamesAreDerivedOnlyFromTheRunId() {
        assertThat(LabDatabase.schemaFor("6D571C15-1b2d-4f26-b64f-242c68b8d550")).isEqualTo("run_6d571c151b2d4f26b64f242c68b8d550");
        assertThat(LabDatabase.schemaFor("x; drop table y")).isEqualTo("run_xdroptabley");
    }
}
