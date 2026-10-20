package dev.forge.server.challenge;

import dev.forge.server.analysis.ComparisonService;
import dev.forge.server.analysis.RegressionService;
import dev.forge.server.run.RunRecord;
import dev.forge.server.run.RunStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Criteria evaluated on synthetic recorded runs, so every PASS and NOT_YET is exact. */
class ChallengeCriteriaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ComparisonService comparisons = new ComparisonService(null);

    private static RunRecord run(int seq, String scenario, String variant, Map<String, Object> params, int trials,
                                 Map<String, double[]> metrics, Boolean held, Map<String, Object> details) {
        List<Map<String, Object>> ts = new ArrayList<>();
        for (int i = 0; i < trials; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (var e : metrics.entrySet()) {
                m.put(e.getKey(), e.getValue()[Math.min(i, e.getValue().length - 1)]);
            }
            ts.add(Map.of("index", i, "metrics", m));
        }
        Map<String, Object> inv = new LinkedHashMap<>();
        inv.put("description", "invariant");
        inv.put("checked", held == null ? 0 : trials);
        inv.put("violations", Boolean.FALSE.equals(held) ? 1 : 0);
        inv.put("held", held);
        JsonNode result = JSON.valueToTree(Map.of("trials", ts, "invariant", inv, "observations", List.of(), "details", details));
        return new RunRecord(UUID.randomUUID(), UUID.randomUUID(), seq, null, "EXPLORATORY", scenario, 1, variant, JSON.valueToTree(params),
                42, trials, 0, 256, 120, RunStatus.SUCCEEDED, null, 0, (long) trials, (long) trials, "measured",
                JSON.valueToTree(Map.of()), result, "test", false, Instant.now(), Instant.now(), Instant.now());
    }

    private List<Criterion.Status> statuses(String challenge, List<RunRecord> runs) {
        Challenges.Definition d = Challenges.find(challenge).orElseThrow();
        Criterion.Context ctx = new Criterion.Context(runs, comparisons, new RegressionService.Report(null, null, null, null, List.of()));
        return d.criteria().stream().map(c -> c.evaluate(ctx).status()).toList();
    }

    private static final Criterion.Status P = Criterion.Status.PASS;
    private static final Criterion.Status N = Criterion.Status.NOT_YET;

    @Test
    void theRaceIsCompleteOnlyAfterReproducingTheBugProvingAFixAndConfirmingItUnderStress() {
        List<RunRecord> runs = new ArrayList<>();
        runs.add(run(1, "concurrency.lost-update", "unsynchronized", Map.of("mode", "explore", "exploreThreads", 2, "exploreDeposits", 1), 1,
                Map.of("schedulesExplored", new double[] {6}, "violatingSchedules", new double[] {4}), false, Map.of("explorer", Map.of("exhaustive", true))));
        assertThat(statuses("race", runs)).containsExactly(P, N, N);

        // a fix explored with only 1 deposit per thread is not enough
        runs.add(run(2, "concurrency.lost-update", "atomic-cas", Map.of("mode", "explore", "exploreThreads", 2, "exploreDeposits", 1), 1,
                Map.of("schedulesExplored", new double[] {6}, "violatingSchedules", new double[] {0}), true, Map.of("explorer", Map.of("exhaustive", true))));
        assertThat(statuses("race", runs)).containsExactly(P, N, N);

        runs.add(run(3, "concurrency.lost-update", "atomic-cas", Map.of("mode", "explore", "exploreThreads", 2, "exploreDeposits", 2), 1,
                Map.of("schedulesExplored", new double[] {90}, "violatingSchedules", new double[] {0}), true, Map.of("explorer", Map.of("exhaustive", true))));
        assertThat(statuses("race", runs)).containsExactly(P, P, N);

        // stress with too few trials, then a variant that was never explored, do not count
        runs.add(run(4, "concurrency.lost-update", "atomic-cas", Map.of("mode", "stress", "threads", 4), 10,
                Map.of("throughputMopsPerSec", new double[] {20}, "lostUpdates", new double[] {0}), true, Map.of()));
        runs.add(run(5, "concurrency.lost-update", "synchronized", Map.of("mode", "stress", "threads", 4), 20,
                Map.of("throughputMopsPerSec", new double[] {20}, "lostUpdates", new double[] {0}), true, Map.of()));
        assertThat(statuses("race", runs)).containsExactly(P, P, N);

        runs.add(run(6, "concurrency.lost-update", "atomic-cas", Map.of("mode", "stress", "threads", 4), 20,
                Map.of("throughputMopsPerSec", new double[] {20}, "lostUpdates", new double[] {0}), true, Map.of()));
        assertThat(statuses("race", runs)).containsExactly(P, P, P);
    }

    @Test
    void theMisleadingBenchmarkJudgesTheMethodNotThePredeterminedOutcome() {
        List<RunRecord> runs = new ArrayList<>();
        runs.add(run(1, "algorithms.lookup", "hash-set", Map.of("size", 16, "hitPercent", 50), 5,
                Map.of("nsPerLookup", new double[] {5.0, 5.1, 5.2, 5.05, 4.95}), null, Map.of()));
        runs.add(run(2, "algorithms.lookup", "linear-array", Map.of("size", 16, "hitPercent", 90), 5,
                Map.of("nsPerLookup", new double[] {2.0, 2.1, 2.05, 1.95, 2.02}), null, Map.of()));
        assertThat(statuses("misleading-benchmark", runs)).containsExactly(N, N, N);   // different hit rate: not comparable

        // the scan turns out SLOWER at 16 elements (as measured in FORGE's container): still a valid, decisive finding
        runs.add(run(3, "algorithms.lookup", "linear-array", Map.of("size", 16, "hitPercent", 50), 5,
                Map.of("nsPerLookup", new double[] {10.7, 10.9, 11.0, 10.8, 10.85}), null, Map.of()));
        assertThat(statuses("misleading-benchmark", runs)).containsExactly(P, N, N);

        // an indistinguishable pair is not decisive
        runs.add(run(4, "algorithms.lookup", "hash-set", Map.of("size", 10_000, "hitPercent", 50), 5,
                Map.of("nsPerLookup", new double[] {6.0, 6.5, 5.5, 6.2, 5.8}), null, Map.of()));
        runs.add(run(5, "algorithms.lookup", "linear-array", Map.of("size", 10_000, "hitPercent", 50), 5,
                Map.of("nsPerLookup", new double[] {6.1, 5.6, 6.4, 5.9, 6.0}), null, Map.of()));
        assertThat(statuses("misleading-benchmark", runs)).containsExactly(P, N, N);
        runs.add(run(6, "algorithms.lookup", "linear-array", Map.of("size", 10_000, "hitPercent", 50), 5,
                Map.of("nsPerLookup", new double[] {1490, 1500, 1510, 1495, 1505}), null, Map.of()));
        assertThat(statuses("misleading-benchmark", runs)).containsExactly(P, P, N);

        runs.add(run(7, "algorithms.lookup", "hash-set", Map.of("size", 16, "hitPercent", 0), 5,
                Map.of("nsPerLookup", new double[] {4.0, 4.1, 4.2, 4.05, 3.95}), null, Map.of()));
        runs.add(run(8, "algorithms.lookup", "linear-array", Map.of("size", 16, "hitPercent", 0), 5,
                Map.of("nsPerLookup", new double[] {14.0, 14.1, 14.2, 14.05, 13.95}), null, Map.of()));
        assertThat(statuses("misleading-benchmark", runs)).containsExactly(P, P, P);
    }

    @Test
    void aRatioWhoseIntervalReachesTheThresholdIsNotEnough() {
        // 4× faster with tight data passes a 5× goal? No: the whole interval must lie below 0.2.
        List<RunRecord> runs = List.of(
                run(1, "database.n-plus-one", "n-plus-one", Map.of("dataSeed", 1, "rows", 200000, "accounts", 100, "days", 30), 5,
                        Map.of("medianLoadMs", new double[] {100, 101, 99, 100, 100.5}), true, Map.of()),
                run(2, "database.n-plus-one", "batched", Map.of("dataSeed", 1, "rows", 200000, "accounts", 100, "days", 30), 5,
                        Map.of("medianLoadMs", new double[] {25, 25.5, 24.5, 25, 25.2}), true, Map.of()));
        assertThat(statuses("slow-api", runs)).containsExactly(P, N, P);
        List<RunRecord> faster = List.of(runs.get(0),
                run(3, "database.n-plus-one", "batched", Map.of("dataSeed", 1, "rows", 200000, "accounts", 100, "days", 30), 5,
                        Map.of("medianLoadMs", new double[] {3.6, 3.7, 3.5, 3.6, 3.65}), true, Map.of()));
        assertThat(statuses("slow-api", faster)).containsExactly(P, P, P);
    }

    @Test
    void theUnstableDependencyDemandsTheSameFaultAndLoad() {
        Map<String, Object> fault = new LinkedHashMap<>(Map.of("fault", "latency", "faultLatencyMs", 800, "errorPercent", 50, "faultStartMs", 1000,
                "faultDurationMs", 2000, "rate", 100, "downstreamWorkers", 4, "downstreamLatencyMs", 10, "requestTimeoutMs", 2000));
        Map<String, Object> noRecovery = Map.of("failure", Map.of("trials", List.of(Map.of("trial", 0), Map.of("trial", 1), Map.of("trial", 2))));
        Map<String, Object> recovered = Map.of("failure", Map.of("trials", List.of(Map.of("trial", 0, "recoveryMs", 560), Map.of("trial", 1, "recoveryMs", 560),
                Map.of("trial", 2, "recoveryMs", 560))));
        List<RunRecord> runs = new ArrayList<>();
        runs.add(run(1, "failure.unstable-dependency", "retry-immediate", fault, 3,
                Map.of("successPercent", new double[] {16.67}, "amplification", new double[] {3.28}), null, noRecovery));
        Map<String, Object> lighter = new LinkedHashMap<>(fault);
        lighter.put("rate", 50);
        runs.add(run(2, "failure.unstable-dependency", "circuit-breaker", lighter, 3,
                Map.of("successPercent", new double[] {80}, "amplification", new double[] {0.6}, "recoveryMs", new double[] {400}), null, recovered));
        assertThat(statuses("unstable-dependency", runs)).containsExactly(P, N);   // a lighter load does not count
        runs.add(run(3, "failure.unstable-dependency", "circuit-breaker", fault, 3,
                Map.of("successPercent", new double[] {58.17}, "amplification", new double[] {0.65}, "recoveryMs", new double[] {560}), null, recovered));
        assertThat(statuses("unstable-dependency", runs)).containsExactly(P, P);
    }

    @Test
    void everyChallengeHasHintsReviewQuestionsAndCriteriaOnAKnownScenario() {
        assertThat(Challenges.all()).hasSize(8);
        assertThat(Challenges.all()).allSatisfy(d -> {
            assertThat(dev.forge.lab.scenario.ScenarioCatalog.find(d.scenarioId())).isPresent();
            var desc = dev.forge.lab.scenario.ScenarioCatalog.find(d.scenarioId()).orElseThrow().descriptor();
            assertThat(desc.variant(d.start().variant())).as(d.id()).isPresent();
            d.start().params().keySet().forEach(k -> assertThat(desc.parameter(k)).as(d.id() + " " + k).isPresent());
            assertThat(d.hints()).isNotEmpty();
            assertThat(d.reviewQuestions()).isNotEmpty();
            assertThat(d.criteria()).hasSizeGreaterThanOrEqualTo(2);
        });
        assertThat(Challenges.all()).extracting(Challenges.Definition::number).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
    }
}
