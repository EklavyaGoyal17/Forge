package dev.forge.lab.scenarios;

import dev.forge.lab.scenario.LabDatabase;
import dev.forge.lab.scenario.ParameterValidator;
import dev.forge.lab.scenario.RunContext;
import dev.forge.lab.scenario.RunSpec;
import dev.forge.lab.scenario.Scenario;
import dev.forge.lab.scenario.ScenarioCatalog;
import dev.forge.lab.scenario.ScenarioResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs Database Studio scenarios against a throwaway PostgreSQL (Testcontainers). The container's
 * user stands in for the restricted lab role, whose own isolation is tested in forge-server.
 */
final class DatabaseScenarioSupport {

    private DatabaseScenarioSupport() {
    }

    static RunContext context(PostgreSQLContainer pg, Path work) {
        LabDatabase lab = new LabDatabase(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        RunContext base = RunContext.noop(work);
        return new RunContext() {
            @Override public void event(String level, String message) { }
            @Override public void progress(long done, long total, String phase) { }
            @Override public void artifact(String name, String contentType, String content) { }
            @Override public Path workDir() { return work; }
            @Override public long deadlineNanos() { return base.deadlineNanos(); }
            @Override public Optional<LabDatabase> labDatabase() { return Optional.of(lab); }
        };
    }

    static ScenarioResult run(PostgreSQLContainer pg, Path work, String scenario, String variant, Map<String, Object> params,
                              int trials, String runId) throws Exception {
        return run(pg, work, scenario, variant, params, trials, runId, 42);
    }

    static ScenarioResult run(PostgreSQLContainer pg, Path work, String scenario, String variant, Map<String, Object> params,
                              int trials, String runId, long runSeed) throws Exception {
        Scenario s = ScenarioCatalog.find(scenario).orElseThrow();
        ParameterValidator.Result v = ParameterValidator.validate(s.descriptor(), variant, params, trials, 0);
        assertThat(v.errors()).isEmpty();
        RunSpec spec = new RunSpec(runId, scenario, s.descriptor().version(), variant, v.params(), runSeed, v.trials(), v.warmupTrials());
        return s.run(spec, context(pg, work));
    }

    static String newRunId() {
        return UUID.randomUUID().toString();
    }

    /** Schemas named like run schemas that are still present (should be none after a run). */
    static long leftoverRunSchemas(PostgreSQLContainer pg) throws Exception {
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from pg_namespace where nspname like 'run\\_%'")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
