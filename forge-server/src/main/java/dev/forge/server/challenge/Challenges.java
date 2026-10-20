package dev.forge.server.challenge;

import dev.forge.server.analysis.RunMetrics;
import dev.forge.server.run.RunRecord;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.forge.server.challenge.Criterion.Result;
import static dev.forge.server.challenge.Criterion.invariantHeld;
import static dev.forge.server.challenge.Criterion.median;
import static dev.forge.server.challenge.Criterion.notVariant;
import static dev.forge.server.challenge.Criterion.param;
import static dev.forge.server.challenge.Criterion.paramIs;
import static dev.forge.server.challenge.Criterion.paramNum;
import static dev.forge.server.challenge.Criterion.ran;
import static dev.forge.server.challenge.Criterion.ratio;
import static dev.forge.server.challenge.Criterion.sameParams;
import static dev.forge.server.challenge.Criterion.variant;

/**
 * The Challenge Arena catalog. Each challenge starts from a measurable problem in a built-in scenario
 * and is complete only when every criterion passes on recorded runs. FORGE runs no uploaded code, so a
 * challenge is solved by finding the right change among the scenario's implementations and proving
 * it with evidence — the same skill as validating a fix in a real system.
 */
public final class Challenges {

    private Challenges() {
    }

    /**
     * @param start the run that reproduces the problem: variant, parameters, trials
     */
    public record Definition(String id, int number, String title, String scenarioId, String problem, String goal, String question,
                             Start start, List<String> hints, List<String> reviewQuestions, List<Criterion> criteria) {
    }

    public record Start(String variant, Map<String, Object> params, int trials) {
    }

    private static final Map<String, Definition> ALL = new LinkedHashMap<>();

    static {
        add(slowApi());
        add(indexing());
        add(race());
        add(memory());
        add(unstableDependency());
        add(poolStarvation());
        add(regressionHunt());
        add(misleadingBenchmark());
    }

    private static void add(Definition d) {
        ALL.put(d.id(), d);
    }

    public static List<Definition> all() {
        return List.copyOf(ALL.values());
    }

    public static Optional<Definition> find(String id) {
        return Optional.ofNullable(ALL.get(id));
    }

    // ------------------------------------------------------------------ 1. slow API (N+1)

    private static Definition slowApi() {
        List<String> same = List.of("dataSeed", "rows", "accounts", "days");
        return new Definition("slow-api", 1, "The slow endpoint", "database.n-plus-one",
                "An endpoint loads 100 accounts with their recent transactions. Each load takes around a hundred milliseconds on a dataset that fits in memory, and the database is not busy.",
                "Make the load at least 5× faster on the same data, without changing what it returns.",
                "Why is loading 100 accounts slow when every individual query is fast?",
                new Start("n-plus-one", Map.of(), 5),
                List.of("Look at queriesPerLoad: how many statements does one load send?",
                        "roundTripMs is the cost of a statement that does nothing. Multiply it by the number of statements.",
                        "Compare what the join and batched variants send to the database (Scenario tab → source)."),
                List.of("Across a real network a round trip costs 0.5–2 ms instead of tens of microseconds. How does that change the result?",
                        "When would a single JOIN be worse than two batched queries?",
                        "How would you detect an N+1 pattern in a production service?"),
                List.of(
                        ran("baseline", "Measure the problem: a successful n-plus-one run", variant("n-plus-one"),
                                r -> "median load " + fmt(median(r, "medianLoadMs")) + " ms, " + fmt(median(r, "queriesPerLoad")) + " statements per load"),
                        ratio("faster", "A different variant loads at least 5× faster on the same data (95% CI of the ratio below 0.2)",
                                variant("n-plus-one"), notVariant("n-plus-one"), same, "medianLoadMs", 0.2, Double.NaN),
                        invariantHeld("correct", "The faster variant returns the same graph as the N+1 version in every trial (≥ 5)",
                                notVariant("n-plus-one"), 5)));
    }

    // ------------------------------------------------------------------ 2. database bottleneck

    private static Definition indexing() {
        List<String> same = List.of("dataSeed", "rows", "accounts", "windowDays");
        return new Definition("index", 2, "The query that wouldn't scale", "database.index-lookup",
                "Showing one account's recent transactions reads the whole transaction table. It was fine at launch; with 500,000 rows it is not.",
                "Make the lookup at least 10× faster with an index, prove the plan uses it, and prove the results did not change.",
                "Which index makes this query fast, and how do you know the planner uses it?",
                new Start("no-index", Map.of(), 5),
                List.of("Read the plan of the baseline: which node reads the most rows?",
                        "The query filters by account and a time window and returns a few columns. Which index serves the filter? Which one could answer without touching the table?",
                        "Compare the probe checksum of two runs on the same dataSeed: equal means the same rows came back."),
                List.of("What does every additional index cost on inserts and updates?",
                        "Why might the planner ignore your index on a much smaller table?",
                        "What changes if the time window covers most of the table?"),
                List.of(
                        ran("baseline", "Measure the problem: a successful no-index run", variant("no-index"),
                                r -> "median query " + fmt(median(r, "medianQueryMs")) + " ms, plan " + plan(r)),
                        ratio("faster", "An indexed variant is at least 10× faster on the same data (95% CI of the ratio below 0.1)",
                                variant("no-index"), notVariant("no-index"), same, "medianQueryMs", 0.1, Double.NaN),
                        new Criterion("plan", "The faster run's plan uses an index scan", ctx -> ctx.runs().reversed().stream()
                                .filter(notVariant("no-index")).filter(r -> plan(r).contains("Index"))
                                .findFirst().map(r -> Result.pass("Run #" + r.seq() + " (" + r.variant() + "): " + plan(r), r.seq()))
                                .orElse(Result.notYet("No indexed run whose plan contains an index scan yet."))),
                        new Criterion("same-rows", "An indexed run returns exactly the rows of a no-index run on the same dataSeed (equal probe checksum)", ctx -> {
                            for (RunRecord c : ctx.runs().reversed()) {
                                if (c.variant().equals("no-index")) {
                                    continue;
                                }
                                for (RunRecord b : ctx.runs()) {
                                    if (b.variant().equals("no-index") && sameParams(b, c, same)) {
                                        JsonNode pb = b.result().path("details").path("database").path("probe");
                                        JsonNode pc = c.result().path("details").path("database").path("probe");
                                        if (pb.path("checksum").isNumber() && pb.path("checksum").asLong() == pc.path("checksum").asLong()
                                                && pb.path("rows").asLong() == pc.path("rows").asLong()) {
                                            return Result.pass("Runs #" + b.seq() + " and #" + c.seq() + ": " + pc.path("rows").asLong()
                                                    + " rows, checksum " + pc.path("checksum").asLong(), b.seq(), c.seq());
                                        }
                                    }
                                }
                            }
                            return Result.notYet("No indexed run with a matching no-index checksum on the same dataset yet.");
                        })));
    }

    // ------------------------------------------------------------------ 3. race condition

    private static Definition race() {
        return new Definition("race", 3, "The invisible race", "concurrency.lost-update",
                "Two threads deposit into a shared balance. Most test runs pass; once in a while the balance is short. Nobody can reproduce it on demand.",
                "Reproduce the lost update deterministically, find an implementation with no violating schedule, and confirm it under real concurrency.",
                "Which implementation preserves every deposit — and what evidence would convince a reviewer?",
                new Start("unsynchronized", Map.of("mode", "explore", "exploreThreads", 2, "exploreDeposits", 1), 1),
                List.of("Explorer mode runs every interleaving of a tiny program. Look at the counterexample trace: which two steps conflict?",
                        "volatile makes each read and write visible — does it make read-then-write atomic?",
                        "A fix must hold in explorer mode with 2 deposits per thread and in at least 20 stress trials."),
                List.of("Why can't twenty passing stress trials prove the absence of a race?",
                        "What does the explorer not model, and why is that acceptable here?",
                        "Which correct implementation would you choose for a hot counter, and why?"),
                List.of(
                        new Criterion("reproduce", "Reproduce the bug: an explorer run of a broken variant finds a violating schedule", ctx -> ctx.runs().stream()
                                .filter(variant("unsynchronized", "volatile")).filter(paramIs("mode", "explore"))
                                .filter(r -> median(r, "violatingSchedules").orElse(0.0) > 0).reduce((a, b) -> b)
                                .map(r -> Result.pass("Run #" + r.seq() + " (" + r.variant() + "): " + fmt(median(r, "violatingSchedules")) + " of "
                                        + fmt(median(r, "schedulesExplored")) + " schedules lose an update", r.seq()))
                                .orElse(Result.notYet("No explorer run of a broken variant has found a violation yet."))),
                        new Criterion("no-schedule", "A different implementation has no violating schedule in an exhaustive exploration with ≥ 2 deposits per thread", ctx -> ctx.runs().reversed().stream()
                                .filter(notVariant("unsynchronized", "volatile")).filter(paramIs("mode", "explore"))
                                .filter(r -> paramNum(r, "exploreDeposits") >= 2)
                                .filter(r -> r.result().path("details").path("explorer").path("exhaustive").asBoolean())
                                .filter(r -> median(r, "violatingSchedules").orElse(1.0) == 0).findFirst()
                                .map(r -> Result.pass("Run #" + r.seq() + " (" + r.variant() + "): " + fmt(median(r, "schedulesExplored"))
                                        + " schedules, all keep every deposit (exhaustive)", r.seq()))
                                .orElse(Result.notYet("No exhaustive explorer run of a fixed variant with 0 violations yet."))),
                        new Criterion("stress", "The same implementation holds under stress: ≥ 20 trials, ≥ 4 threads, no lost update", ctx -> {
                            for (RunRecord s : ctx.runs().reversed()) {
                                if (!"stress".equals(param(s, "mode")) || paramNum(s, "threads") < 4 || s.trials() < 20
                                        || !Boolean.TRUE.equals(RunMetrics.invariantHeld(s))) {
                                    continue;
                                }
                                boolean explored = ctx.runs().stream().anyMatch(e -> e.variant().equals(s.variant()) && "explore".equals(param(e, "mode"))
                                        && paramNum(e, "exploreDeposits") >= 2 && median(e, "violatingSchedules").orElse(1.0) == 0);
                                if (explored) {
                                    return Result.pass("Run #" + s.seq() + " (" + s.variant() + "): " + s.trials() + " trials with "
                                            + (int) paramNum(s, "threads") + " threads, " + fmt(median(s, "lostUpdates")) + " lost updates (median)", s.seq());
                                }
                            }
                            return Result.notYet("No stress run (≥ 20 trials, ≥ 4 threads) of an explorer-verified variant has held the invariant yet.");
                        })));
    }

    // ------------------------------------------------------------------ 4. memory pressure

    private static Definition memory() {
        List<String> same = List.of("mode", "elements");
        return new Definition("memory", 4, "Allocation pressure", "jvm.allocation",
                "A report sums a million values per request. It is not slow, but the service's GC log is busy and its allocation rate dominates the profile.",
                "Cut the bytes allocated per operation by at least 100× while computing the same result.",
                "Where do the allocated bytes come from, and what is the smallest change that removes them?",
                new Start("boxed-stream", Map.of("mode", "aggregate"), 5),
                List.of("The JVM tab shows allocation by class and by site. Which class dominates?",
                        "Long.valueOf on every element allocates unless the value is cached. How large is the cache?",
                        "IntStream and a plain loop never box."),
                List.of("Why does lower allocation not always mean lower latency?",
                        "What could escape analysis do to the boxed version in a different JIT state?",
                        "How would you find this pattern in a production heap profile?"),
                List.of(
                        ran("baseline", "Measure the problem: a successful boxed-stream run", variant("boxed-stream"),
                                r -> fmt(median(r, "allocatedMbPerOp")) + " MB allocated per operation, " + fmt(median(r, "gcCount")) + " GCs per trial"),
                        ratio("allocation", "A different variant allocates at least 100× less per operation on the same input (95% CI below 0.01)",
                                variant("boxed-stream"), notVariant("boxed-stream"), same, "allocatedMbPerOp", 0.01, Double.NaN),
                        invariantHeld("same-result", "That variant computes the same result in every trial (≥ 5)", notVariant("boxed-stream").and(paramIs("mode", "aggregate")), 5)));
    }

    // ------------------------------------------------------------------ 5. unstable dependency

    private static Definition unstableDependency() {
        List<String> same = List.of("fault", "faultLatencyMs", "errorPercent", "faultStartMs", "faultDurationMs", "rate", "downstreamWorkers",
                "downstreamLatencyMs", "requestTimeoutMs");
        return new Definition("unstable-dependency", 5, "Unstable dependency", "failure.unstable-dependency",
                "The inventory service slowed to 800 ms for two seconds. The caller retries failures immediately, three times. The dependency recovered; the caller did not.",
                "Under the same fault, make the caller recover in every trial while serving at least half of all requests, without amplifying load on the dependency.",
                "Why did the outage outlive the fault, and which policy ends it?",
                new Start("retry-immediate", Map.of("fault", "latency"), 3),
                List.of("Look at the dependency backlog in the timeline after the fault ends: is it shrinking?",
                        "(1 + retries) × offered rate versus the dependency's healthy capacity: how much headroom is left?",
                        "Backoff spreads retries out; a budget or a breaker reduces how many there are."),
                List.of("Why does exponential backoff with jitter alone not prevent the storm here?",
                        "What does a circuit breaker that opens too eagerly cost?",
                        "How would you choose a retry budget for a real service?"),
                List.of(
                        new Criterion("storm", "Reproduce the storm: a retry-immediate run under the latency fault that did not recover in at least one trial", ctx -> ctx.runs().stream()
                                .filter(variant("retry-immediate")).filter(paramIs("fault", "latency"))
                                .filter(r -> unrecovered(r) > 0).reduce((a, b) -> b)
                                .map(r -> Result.pass("Run #" + r.seq() + ": no recovery in " + unrecovered(r) + " of " + r.result().path("details").path("failure").path("trials").size()
                                        + " trials, " + fmt(median(r, "amplification")) + " attempts per request", r.seq()))
                                .orElse(Result.notYet("No retry-immediate run under the latency fault has failed to recover yet."))),
                        new Criterion("recovers", "Under the same fault and load, another policy recovers in every trial (≥ 3), serves ≥ 50% of requests and sends ≤ 1.2 attempts per request", ctx -> {
                            for (RunRecord c : ctx.runs().reversed()) {
                                if (c.variant().equals("retry-immediate") || !"latency".equals(param(c, "fault"))) {
                                    continue;
                                }
                                Optional<RunRecord> base = ctx.runs().stream().filter(variant("retry-immediate")).filter(b -> sameParams(b, c, same)).reduce((a, b) -> b);
                                int trials = c.result().path("details").path("failure").path("trials").size();
                                double success = median(c, "successPercent").orElse(0.0);
                                double amp = median(c, "amplification").orElse(99.0);
                                if (base.isPresent() && trials >= 3 && unrecovered(c) == 0 && success >= 50 && amp <= 1.2) {
                                    return Result.pass("Run #" + c.seq() + " (" + c.variant() + "): recovered in " + trials + " of " + trials + " trials (median "
                                            + fmt(median(c, "recoveryMs")) + " ms), success " + fmt(Optional.of(success)) + "%, " + fmt(Optional.of(amp))
                                            + " attempts per request; same fault and load as run #" + base.get().seq(), base.get().seq(), c.seq());
                                }
                            }
                            return Result.notYet("No policy has met all of: same fault and load as a retry-immediate run, recovery in every trial (≥ 3), ≥ 50% success, ≤ 1.2 attempts per request.");
                        })));
    }

    private static long unrecovered(RunRecord r) {
        long n = 0;
        for (JsonNode t : r.result().path("details").path("failure").path("trials")) {
            n += t.path("recoveryMs").isNumber() ? 0 : 1;
        }
        return n;
    }

    // ------------------------------------------------------------------ 6. thread-pool starvation

    private static Definition poolStarvation() {
        List<String> same = List.of("rate", "poolSize", "downstreamMs", "cpuWorkUs");
        return new Definition("pool-starvation", 6, "Thread-pool starvation", "performance.thread-pool-starvation",
                "A service with 4 worker threads waits 20 ms on a downstream call per request. At 300 req/s its CPUs are mostly idle, yet p99 latency is two seconds and requests time out.",
                "Serve the full offered rate with no errors and at least 10× lower p99 — without adding worker threads.",
                "If the CPUs are idle, what is the service waiting for, and how much can 4 threads complete per second?",
                new Start("blocking-pool", Map.of(), 5),
                List.of("Little's law: 4 threads that are each busy for about 20 ms can complete at most how many requests per second?",
                        "Compare server service time with queue wait: where does the latency come from?",
                        "Two variants keep the same number of threads but stop them from waiting on the downstream call."),
                List.of("Compute the Little's-law bound for the baseline and compare it with achieved throughput.",
                        "What do virtual threads not fix (CPU-bound work, pinned carriers, a limited connection pool)?",
                        "Why does a bigger pool only move the problem?"),
                List.of(
                        new Criterion("saturated", "Measure the problem: a blocking-pool run that completes less than 95% of the offered rate", ctx -> ctx.runs().stream()
                                .filter(variant("blocking-pool")).filter(r -> median(r, "achievedRps").orElse(0.0) < 0.95 * median(r, "offeredRps").orElse(0.0))
                                .reduce((a, b) -> b)
                                .map(r -> Result.pass("Run #" + r.seq() + ": " + fmt(median(r, "achievedRps")) + " of " + fmt(median(r, "offeredRps")) + " req/s, p99 "
                                        + fmt(median(r, "latencyP99Ms")) + " ms; Little's-law bound " + fmt(median(r, "littleBoundRps")) + " req/s", r.seq()))
                                .orElse(Result.notYet("No saturated blocking-pool run yet."))),
                        ratio("tail", "A variant on the same workload has at least 10× lower p99 (95% CI of the ratio below 0.1)",
                                variant("blocking-pool"), notVariant("blocking-pool"), same, "latencyP99Ms", 0.1, Double.NaN),
                        new Criterion("throughput", "That variant completes ≥ 99% of the offered rate with 0 errors in every trial, using no more threads than the pool size", ctx -> {
                            for (RunRecord r : ctx.runs().reversed()) {
                                if (r.variant().equals("blocking-pool")) {
                                    continue;
                                }
                                int workers = r.result().path("details").path("httpLoad").path("workers").asInt(-1);
                                boolean noMoreThreads = workers == 0 || workers <= paramNum(r, "poolSize");
                                double maxErrors = RunMetrics.samples(r).getOrDefault("errorPercent", new double[] {100}).length == 0 ? 100
                                        : java.util.Arrays.stream(RunMetrics.samples(r).get("errorPercent")).max().orElse(100);
                                boolean full = median(r, "achievedRps").orElse(0.0) >= 0.99 * median(r, "offeredRps").orElse(Double.MAX_VALUE);
                                if (noMoreThreads && full && maxErrors == 0) {
                                    return Result.pass("Run #" + r.seq() + " (" + r.variant() + "): " + fmt(median(r, "achievedRps")) + " of " + fmt(median(r, "offeredRps"))
                                            + " req/s, 0% errors in every trial, " + (workers == 0 ? "virtual threads (no pool)" : workers + " workers"), r.seq());
                                }
                            }
                            return Result.notYet("No variant has served ≥ 99% of the offered rate with 0 errors without more threads than the pool size.");
                        })));
    }

    // ------------------------------------------------------------------ 7. performance regression

    private static Definition regressionHunt() {
        return new Definition("regression", 7, "Find the regression", "algorithms.lookup",
                "Lookups are the hot path. Four implementations have been proposed over time; one of them made a release slower, and a 'feels faster' review let another through. You need a gate, not an opinion.",
                "Pin a baseline, measure every other implementation on the same workload, and let the regression gate separate regressions from noise.",
                "Which implementation regresses lookups at this size, by how much, and how sure are you?",
                new Start("hash-set", Map.of("size", 10_000), 5),
                List.of("Pin the hash-set run as the baseline on the Runs tab.",
                        "Keep size and hit percentage identical: the gate refuses runs whose parameters differ.",
                        "A gate verdict of no difference is not equivalence; it means the interval contains 1 at this sample size."),
                List.of("Why does the gate refuse to compare runs with different parameters?",
                        "Would this regression show up at size 16? At size 1,000,000?",
                        "How would you wire such a gate into CI without making it flaky?"),
                List.of(
                        new Criterion("pinned", "A successful baseline run is pinned", ctx -> ctx.gate().baselineRunId() == null
                                ? Result.notYet("No baseline pinned yet (Runs tab → pin a succeeded run).")
                                : Result.pass("Run #" + ctx.gate().baselineSeq() + " (" + ctx.gate().baselineVariant() + ") is the pinned baseline.", ctx.gate().baselineSeq())),
                        new Criterion("gated", "At least three other implementations are gated against it on the same parameters", ctx -> {
                            var comparable = ctx.gate().checks().stream()
                                    .filter(c -> c.gate() != dev.forge.server.analysis.RegressionService.Gate.NOT_COMPARABLE)
                                    .filter(c -> !c.variant().equals(ctx.gate().baselineVariant())).toList();
                            long variants = comparable.stream().map(c -> c.variant()).distinct().count();
                            return variants >= 3
                                    ? new Result(Criterion.Status.PASS, variants + " implementations gated: " + comparable.stream()
                                            .map(c -> "#" + c.seq() + " " + c.variant() + " " + c.gate()).distinct().toList(),
                                            comparable.stream().map(c -> c.seq()).toList())
                                    : Result.notYet(variants + " of 3 other implementations gated on the same parameters.");
                        }),
                        new Criterion("found", "The gate reports at least one REGRESSION and at least one comparable run that is not a regression", ctx -> {
                            var checks = ctx.gate().checks().stream()
                                    .filter(c -> c.gate() != dev.forge.server.analysis.RegressionService.Gate.NOT_COMPARABLE).toList();
                            var regressions = checks.stream().filter(c -> c.gate() == dev.forge.server.analysis.RegressionService.Gate.REGRESSION).toList();
                            boolean other = checks.stream().anyMatch(c -> c.gate() != dev.forge.server.analysis.RegressionService.Gate.REGRESSION);
                            if (!regressions.isEmpty() && other) {
                                var r = regressions.getFirst();
                                return Result.pass("Run #" + r.seq() + " (" + r.variant() + ") regresses: " + (r.primary() == null ? ""
                                        : String.format(java.util.Locale.ROOT, "ratio %.4g (95%% CI %.4g–%.4g)", r.primary().ratioOfMedians(), r.primary().ciLow(), r.primary().ciHigh())), r.seq());
                            }
                            return Result.notYet("The gate has not separated a regression from a non-regression yet.");
                        })));
    }

    // ------------------------------------------------------------------ 8. misleading benchmark

    private static Definition misleadingBenchmark() {
        List<String> same = List.of("size", "hitPercent");
        java.util.function.Predicate<RunRecord> small = r -> paramNum(r, "size") <= 64;
        java.util.function.Predicate<RunRecord> large = r -> paramNum(r, "size") >= 10_000;
        return new Definition("misleading-benchmark", 8, "The misleading benchmark", "algorithms.lookup",
                "A pull request replaces a HashSet with a linear scan over an array, citing a microbenchmark at 16 elements where the scan was faster on the author's laptop.",
                "Decide with measurements whether the claim holds on this machine, whether it survives a production-like size, and whether it depends on the hit rate. "
                        + "The criteria judge the method, not the outcome: whatever you measure is the finding.",
                "Is the linear scan faster? Under which conditions — and what would you ask the author to measure?",
                new Start("linear-array", Map.of("size", 16), 5),
                List.of("Run hash-set and linear-array at the same small size, with the same hit percentage.",
                        "Repeat both at a size your production data might reach.",
                        "Misses cost a linear scan the most: compare an all-hit and an all-miss workload at the small size."),
                List.of("What did you find at 16 elements — and why might it differ from the author's laptop (CPU, JDK, JIT, hit rate)?",
                        "Why can a linear scan beat hashing at small sizes in principle, and what would change with object keys or cache misses?",
                        "What evidence would you require before merging the pull request?"),
                List.of(
                        decisive("small", "The claim tested at a size of at most 64: linear-array vs hash-set on the same workload, with a 95% interval that excludes 1 (either way)",
                                variant("hash-set").and(small), variant("linear-array").and(small), same),
                        decisive("large", "The same comparison at a size of at least 10,000, with an interval that excludes 1",
                                variant("hash-set").and(large), variant("linear-array").and(large), same),
                        new Criterion("distribution", "At the small size, decisive comparisons at two different hit percentages (does the answer depend on the input?)", ctx -> {
                            java.util.Map<String, String> byHit = new java.util.LinkedHashMap<>();
                            java.util.List<Integer> runs = new ArrayList<>();
                            for (RunRecord c : ctx.runs()) {
                                if (!c.variant().equals("linear-array") || !small.test(c)) {
                                    continue;
                                }
                                Optional<RunRecord> b = ctx.runs().stream().filter(variant("hash-set").and(small)).filter(x -> sameParams(x, c, same))
                                        .reduce((x, y) -> y);
                                if (b.isEmpty()) {
                                    continue;
                                }
                                var m = ctx.comparisons().compare(b.get(), c).metrics().stream().map(x -> x.result())
                                        .filter(x -> x.metric().equals("nsPerLookup")).findFirst();
                                if (m.isPresent() && (m.get().ciHigh() < 1 || m.get().ciLow() > 1)) {
                                    byHit.put(param(c, "hitPercent"), String.format(java.util.Locale.ROOT, "hit %s%%: ratio %.3g (%s)", param(c, "hitPercent"),
                                            m.get().ratioOfMedians(), m.get().ciHigh() < 1 ? "linear faster" : "linear slower"));
                                    runs.add(b.get().seq());
                                    runs.add(c.seq());
                                }
                            }
                            return byHit.size() >= 2
                                    ? new Result(Criterion.Status.PASS, String.join("; ", byHit.values()), runs.stream().distinct().toList())
                                    : Result.notYet(byHit.size() + " of 2 hit percentages compared decisively at the small size" + (byHit.isEmpty() ? "." : ": " + String.join("; ", byHit.values()) + "."));
                        })));
    }

    /** A comparison whose 95% interval excludes 1 in either direction; the evidence says which way. */
    private static Criterion decisive(String id, String description, java.util.function.Predicate<RunRecord> baseline,
                                      java.util.function.Predicate<RunRecord> candidate, List<String> same) {
        return new Criterion(id, description, ctx -> {
            List<String> tried = new ArrayList<>();
            for (RunRecord c : ctx.runs().reversed()) {
                if (!candidate.test(c)) {
                    continue;
                }
                Optional<RunRecord> b = ctx.runs().stream().filter(baseline).filter(x -> sameParams(x, c, same)).reduce((x, y) -> y);
                if (b.isEmpty()) {
                    tried.add("run #" + c.seq() + " has no hash-set run with the same " + same);
                    continue;
                }
                var m = ctx.comparisons().compare(b.get(), c).metrics().stream().map(x -> x.result()).filter(x -> x.metric().equals("nsPerLookup")).findFirst();
                if (m.isEmpty()) {
                    continue;
                }
                String text = String.format(java.util.Locale.ROOT, "run #%d (size %s, hit %s%%) vs #%d: linear/hash ratio of medians %.4g (95%% CI %.4g–%.4g)",
                        c.seq(), param(c, "size"), param(c, "hitPercent"), b.get().seq(), m.get().ratioOfMedians(), m.get().ciLow(), m.get().ciHigh());
                if (m.get().ciHigh() < 1) {
                    return Result.pass(text + " — the linear scan is faster here", b.get().seq(), c.seq());
                }
                if (m.get().ciLow() > 1) {
                    return Result.pass(text + " — the linear scan is slower here", b.get().seq(), c.seq());
                }
                tried.add(text + " — the interval contains 1");
            }
            return Result.notYet(tried.isEmpty() ? "No comparable pair yet." : "Not decisive yet: " + String.join("; ", tried.subList(0, Math.min(2, tried.size()))) + ".");
        });
    }

    // ------------------------------------------------------------------ helpers

    static String plan(RunRecord r) {
        JsonNode plans = r.result().path("details").path("database").path("plans");
        List<String> nodes = new ArrayList<>();
        if (plans.isArray() && !plans.isEmpty()) {
            plans.get(0).path("nodes").forEach(n -> nodes.add(n.asString()));
        }
        return nodes.isEmpty() ? "(no plan recorded)" : String.join(" → ", nodes);
    }

    static String fmt(Optional<Double> v) {
        return v.map(Challenges::format).orElse("?");
    }

    private static String format(double x) {
        if (x == Math.rint(x) && Math.abs(x) < 1e15) {
            return Long.toString((long) x);
        }
        return new java.math.BigDecimal(x).round(new java.math.MathContext(4)).stripTrailingZeros().toPlainString();
    }
}
