package dev.forge.lab.scenarios;

import dev.forge.lab.scenario.ScenarioResult;
import dev.forge.lab.scenarios.database.IndexLookupScenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Database Studio scenarios against a real PostgreSQL. Assertions are about structure, result
 * equivalence and plan shape — never about timings.
 */
@Testcontainers(disabledWithoutDocker = true)
class DatabaseScenariosTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @TempDir
    Path work;

    private static final Map<String, Object> SMALL = Map.of("rows", 60_000, "accounts", 500, "windowDays", 180, "queriesPerTrial", 20);

    @SuppressWarnings("unchecked")
    private static Map<String, Object> database(ScenarioResult r) {
        return (Map<String, Object>) r.details().get("database");
    }

    @ParameterizedTest
    @CsvSource({
            "no-index, Seq Scan",
            "account-index, txn_account_idx",
            "composite-index, txn_account_created_idx",
            "covering-index, Index Only Scan using txn_account_created_cov"})
    @SuppressWarnings("unchecked")
    void indexVariantsReturnTheSameRowsThroughDifferentAccessPaths(String variant, String expectedPath) throws Exception {
        ScenarioResult r = DatabaseScenarioSupport.run(postgres, work, IndexLookupScenario.ID, variant, SMALL, 2,
                DatabaseScenarioSupport.newRunId());
        assertThat(r.trials()).hasSize(2);
        assertThat(r.trials()).allSatisfy(t -> {
            assertThat(t.metrics()).containsKeys("medianQueryMs", "p95QueryMs", "explainExecutionMs", "sharedHitBlocks", "rowsPerQuery");
            assertThat(t.note()).contains(expectedPath);
        });
        Map<String, Object> db = database(r);
        assertThat((Map<String, Object>) db.get("postgres")).containsKey("server_version");
        assertThat(((Map<String, Object>) db.get("dataset")).get("txnRows")).isEqualTo(60_000L);
        assertThat((List<?>) db.get("plans")).hasSize(2);
        Map<String, Object> plan = (Map<String, Object>) ((List<Map<String, Object>>) db.get("plans")).get(0).get("plan");
        assertThat(plan).containsKey("Node Type");
        if (variant.equals("no-index")) {
            assertThat(db.get("index")).isNull();
        } else {
            assertThat((Map<String, Object>) db.get("index")).containsKeys("ddl", "buildMs", "sizeMb");
        }
        // the per-run schema is gone afterwards
        assertThat(DatabaseScenarioSupport.leftoverRunSchemas(postgres)).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void sameSeedSameDataSoEveryVariantSeesTheSameProbeResult() throws Exception {
        Set<Object> checksums = new HashSet<>();
        for (String variant : List.of("no-index", "covering-index")) {
            ScenarioResult r = DatabaseScenarioSupport.run(postgres, work, IndexLookupScenario.ID, variant, SMALL, 1,
                    DatabaseScenarioSupport.newRunId());
            Map<String, Object> probe = (Map<String, Object>) database(r).get("probe");
            assertThat((Long) probe.get("rows")).isPositive();
            checksums.add(probe.get("checksum"));
        }
        assertThat(checksums).hasSize(1);
    }

    @Test
    void refusesToRunWithoutTheLabDatabase() {
        var s = dev.forge.lab.scenario.ScenarioCatalog.find(IndexLookupScenario.ID).orElseThrow();
        var v = dev.forge.lab.scenario.ParameterValidator.validate(s.descriptor(), "no-index", SMALL, 1, 0);
        var spec = new dev.forge.lab.scenario.RunSpec("x", IndexLookupScenario.ID, 1, "no-index", v.params(), 1, 1, 0);
        assertThatThrownBy(() -> s.run(spec, dev.forge.lab.scenario.RunContext.noop(work)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lab database");
    }
}
