package dev.forge.lab.scenarios;

import dev.forge.lab.scenario.ScenarioResult;
import dev.forge.lab.scenarios.database.IndexLookupScenario;
import dev.forge.lab.scenarios.database.NPlusOneScenario;
import dev.forge.lab.scenarios.database.PaginationScenario;
import dev.forge.lab.scenarios.database.SqlLostUpdateScenario;
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
    @SuppressWarnings("unchecked")
    void offsetReadsEveryEarlierRowWhileKeysetSeeksAndBothReturnTheSamePages() throws Exception {
        Map<String, Object> params = Map.of("rows", 40_000, "pageSize", 20, "page", 1_000, "pagesPerTrial", 5);
        ScenarioResult offset = DatabaseScenarioSupport.run(postgres, work, PaginationScenario.ID, "offset", params, 2, DatabaseScenarioSupport.newRunId());
        ScenarioResult keyset = DatabaseScenarioSupport.run(postgres, work, PaginationScenario.ID, "keyset", params, 2, DatabaseScenarioSupport.newRunId());
        for (ScenarioResult r : List.of(offset, keyset)) {
            assertThat(r.invariant().held()).isTrue();
            assertThat(r.trials()).allSatisfy(t -> assertThat(t.metrics().get("pageMismatches")).isZero());
            List<Map<String, Object>> sweep = (List<Map<String, Object>>) database(r).get("sweep");
            assertThat(sweep).extracting(p -> p.get("page")).containsExactly(1L, 10L, 100L, 1_000L);
        }
        // page 1000 of 20: OFFSET must produce the 19,980 rows it skips, keyset only the page
        assertThat(offset.trials()).allSatisfy(t -> assertThat(t.metrics().get("rowsScanned")).isGreaterThanOrEqualTo(19_980.0));
        assertThat(keyset.trials()).allSatisfy(t -> assertThat(t.metrics().get("rowsScanned")).isLessThan(100.0));
        // both start the trial at the same row
        assertThat(keyset.trials().get(0).note().replaceAll("^.*; ", "")).isEqualTo(offset.trials().get(0).note().replaceAll("^.*; ", ""));
    }

    @Test
    void paginationRefusesPagesBeyondTheData() {
        assertThatThrownBy(() -> DatabaseScenarioSupport.run(postgres, work, PaginationScenario.ID, "offset",
                Map.of("rows", 10_000, "pageSize", 50, "page", 1_000, "pagesPerTrial", 5), 1, DatabaseScenarioSupport.newRunId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only 10000 are seeded");
    }

    @ParameterizedTest
    @CsvSource({"n-plus-one, 31", "join, 1", "batched, 2"})
    void everyLoadingStrategyLoadsTheSameGraph(String variant, double statements) throws Exception {
        ScenarioResult r = DatabaseScenarioSupport.run(postgres, work, NPlusOneScenario.ID, variant,
                Map.of("rows", 30_000, "accounts", 30, "days", 120, "loadsPerTrial", 3), 2, DatabaseScenarioSupport.newRunId());
        assertThat(r.invariant().held()).isTrue();
        assertThat(r.trials()).allSatisfy(t -> {
            assertThat(t.metrics().get("queriesPerLoad")).isEqualTo(statements);
            assertThat(t.metrics().get("rowsPerLoad")).isPositive();
            assertThat(t.metrics().get("roundTripMs")).isPositive();
        });
    }

    @ParameterizedTest
    @CsvSource({"select-for-update", "atomic-update", "repeatable-read-retry", "serializable-retry"})
    void correctSqlDepositPatternsLoseNothing(String variant) throws Exception {
        ScenarioResult r = DatabaseScenarioSupport.run(postgres, work, SqlLostUpdateScenario.ID, variant,
                Map.of("workers", 4, "depositsPerWorker", 50, "hotRows", 1), 2, DatabaseScenarioSupport.newRunId());
        assertThat(r.invariant().held()).isTrue();
        assertThat(r.trials()).allSatisfy(t -> assertThat(t.metrics().get("lostDeposits")).isZero());
        assertThat(DatabaseScenarioSupport.leftoverRunSchemas(postgres)).isZero();
    }

    @Test
    void racyReadModifyWriteReportsStructureOnly() throws Exception {
        // whether deposits are lost is timing-dependent; only the accounting is asserted
        ScenarioResult r = DatabaseScenarioSupport.run(postgres, work, SqlLostUpdateScenario.ID, "read-modify-write",
                Map.of("workers", 4, "depositsPerWorker", 50, "hotRows", 1), 2, DatabaseScenarioSupport.newRunId());
        assertThat(r.trials()).hasSize(2).allSatisfy(t -> {
            assertThat(t.metrics().get("lostDeposits")).isBetween(0.0, 199.0);
            assertThat(t.invariantHeld()).isEqualTo(t.metrics().get("lostDeposits") == 0.0);
        });
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
