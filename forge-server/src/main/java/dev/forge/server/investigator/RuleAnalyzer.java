package dev.forge.server.investigator;

import dev.forge.lab.scenario.ScenarioCatalog;
import dev.forge.lab.scenario.ScenarioDescriptor;
import dev.forge.lab.stats.Comparison;
import dev.forge.lab.stats.Summary;
import dev.forge.server.analysis.ComparisonService;
import dev.forge.server.analysis.RunMetrics;
import dev.forge.server.experiment.Experiment;
import dev.forge.server.investigator.Evidence.Finding;
import dev.forge.server.run.RunRecord;
import dev.forge.server.run.RunStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.forge.server.investigator.EvidenceBuilder.n;

/**
 * The investigator that works without any AI provider: explicit rules over the stored evidence.
 * Each finding says which rule produced it and cites the evidence items it rests on. Rules never
 * name a root cause as fact — explanations are HYPOTHESIS, and come with the experiment that would
 * test them.
 */
@Component
public class RuleAnalyzer {

    static final double NOISY_CV = 0.10;
    static final int SMALL_N = 5;
    static final double TAIL_RATIO = 3.0;

    private final ComparisonService comparisons;

    public RuleAnalyzer(ComparisonService comparisons) {
        this.comparisons = comparisons;
    }

    public List<Finding> analyze(Experiment e, List<RunRecord> allRuns, Evidence.Bundle bundle) {
        List<Finding> out = new ArrayList<>();
        Map<String, Evidence.Item> ev = bundle.byId();
        Optional<ScenarioDescriptor> d = ScenarioCatalog.find(e.scenarioId()).map(s -> s.descriptor());
        String primary = d.map(ScenarioDescriptor::primaryMetric).orElse(null);
        List<RunRecord> runs = allRuns.stream().filter(r -> ev.containsKey("R" + r.seq())).sorted(Comparator.comparingInt(RunRecord::seq)).toList();
        List<RunRecord> ok = runs.stream().filter(r -> r.status() == RunStatus.SUCCEEDED && r.result() != null).toList();

        if (e.hypothesis() == null || e.hypothesis().isBlank()) {
            out.add(new Finding("CAVEAT", "no-hypothesis", "No hypothesis is recorded, so there is nothing to confirm or refute — results can only be described.", List.of("Q")));
        }
        if (runs.isEmpty()) {
            out.add(new Finding("NEXT_EXPERIMENT", "no-runs", "Nothing has been measured yet. Run the baseline variant with the default parameters first.", List.of("Q")));
            return out;
        }
        for (RunRecord r : runs) {
            if (r.status() != RunStatus.SUCCEEDED) {
                out.add(new Finding("OBSERVATION", "run-not-successful", "Run #" + r.seq() + " ended " + r.status()
                        + (r.statusReason() == null ? "" : ": " + r.statusReason()) + ". It contributes no measurements.", List.of("R" + r.seq())));
            }
        }

        for (RunRecord r : ok) {
            String id = "R" + r.seq();
            Map<String, Summary> s = RunMetrics.summaries(r);
            Summary p = primary == null ? null : s.get(primary);
            if (p != null) {
                String unit = d.flatMap(x -> x.metric(primary)).map(m -> m.unit()).orElse("");
                out.add(new Finding("OBSERVATION", "primary-metric", "Run #" + r.seq() + " (" + r.variant() + "): median " + primary + " "
                        + n(p.median()) + " " + unit + " over " + p.n() + " trials (range " + n(p.min()) + "–" + n(p.max()) + ").",
                        List.of(id + ".m." + primary)));
                if (Double.isFinite(p.cv()) && p.cv() > NOISY_CV) {
                    out.add(new Finding("CAVEAT", "noisy-metric", "Run #" + r.seq() + "'s " + primary + " varies by " + n(100 * p.cv())
                            + "% (coefficient of variation) between trials. Differences smaller than that are hard to distinguish from noise; look for background load, frequency scaling, GC or timer effects.",
                            List.of(id + ".m." + primary, id + ".env")));
                }
                if (p.n() < SMALL_N && !"explore".equals(r.params().path("mode").asString(""))) {
                    out.add(new Finding("CAVEAT", "small-sample", "Run #" + r.seq() + " has only " + p.n() + " measured trials; the bootstrap interval and the rank test are unreliable below "
                            + SMALL_N + ".", List.of(id)));
                }
            }
            Boolean held = RunMetrics.invariantHeld(r);
            if (Boolean.FALSE.equals(held)) {
                out.add(new Finding("OBSERVATION", "invariant-violated", "Run #" + r.seq() + " (" + r.variant() + ") violated the invariant: " + RunMetrics.invariantText(r) + ".",
                        List.of(id + ".inv")));
                out.add(new Finding("CAVEAT", "correctness-first", "A variant that violates the invariant is not a valid performance candidate: being faster while wrong is not an improvement.",
                        List.of(id + ".inv")));
            }
            tailRules(out, r, s, id);
            labRules(out, r, id);
        }

        comparisonRules(out, ok, ev);
        nextExperiments(out, e, ok, d, ev);
        d.ifPresent(desc -> {
            if (!desc.caveats().isEmpty()) {
                out.add(new Finding("CAVEAT", "scenario-caveats", "This scenario has " + desc.caveats().size()
                        + " documented limitations that bound how far any conclusion generalises — read them before deciding.", List.of("S")));
            }
        });
        return out;
    }

    /** A p99 far above the p50 of the same quantity suggests waiting, not computing. */
    private void tailRules(List<Finding> out, RunRecord r, Map<String, Summary> s, String id) {
        for (String k : s.keySet()) {
            if (!k.contains("P99")) {
                continue;
            }
            String p50 = k.replace("P99", "P50");
            if (!s.containsKey(p50)) {
                continue;
            }
            double hi = s.get(k).median();
            double lo = s.get(p50).median();
            if (lo > 0 && hi / lo >= TAIL_RATIO) {
                out.add(new Finding("OBSERVATION", "heavy-tail", "Run #" + r.seq() + ": median " + k + " is " + n(hi / lo) + "× " + p50 + " (" + n(hi) + " vs " + n(lo) + ").",
                        List.of(id + ".m." + k, id + ".m." + p50)));
                out.add(new Finding("HYPOTHESIS", "heavy-tail", "A tail that far above the median usually means some requests waited — in a queue, behind a GC pause, a lock, a timeout or a retry — rather than doing more work. Compare queue wait and service time, or GC pauses, for the same run to tell which.",
                        List.of(id + ".m." + k, id + ".detail")));
                return;
            }
        }
    }

    private void labRules(List<Finding> out, RunRecord r, String id) {
        JsonNode details = r.result().path("details");
        Map<String, Summary> s = RunMetrics.summaries(r);
        if (details.path("httpLoad").isObject() && s.containsKey("achievedRps") && s.containsKey("offeredRps")) {
            double achieved = s.get("achievedRps").median();
            double offered = s.get("offeredRps").median();
            if (achieved < 0.95 * offered) {
                out.add(new Finding("OBSERVATION", "saturation", "Run #" + r.seq() + " completed " + n(achieved) + " req/s of " + n(offered) + " offered: the service could not keep up.",
                        List.of(id + ".m.achievedRps", id + ".m.offeredRps")));
                if (s.containsKey("littleBoundRps")) {
                    double bound = s.get("littleBoundRps").median();
                    if (Math.abs(achieved - bound) <= 0.1 * bound) {
                        out.add(new Finding("HYPOTHESIS", "pool-bound", "Achieved throughput is within 10% of the Little's-law bound of the worker pool (" + n(bound)
                                + " req/s), which points at the pool — threads held for the whole request — as the limit rather than the CPU. Doubling the pool or removing the blocking wait should move the plateau if so.",
                                List.of(id + ".m.achievedRps", id + ".m.littleBoundRps")));
                    }
                }
            }
        }
        JsonNode failure = details.path("failure");
        if (failure.isObject() && !"none".equals(failure.path("config").path("fault").asString(""))) {
            int trials = failure.path("trials").size();
            long unrecovered = 0;
            for (JsonNode t : failure.path("trials")) {
                unrecovered += t.path("recoveryMs").isNumber() ? 0 : 1;
            }
            if (unrecovered > 0) {
                out.add(new Finding("OBSERVATION", "no-recovery", "Run #" + r.seq() + " did not recover before load ended in " + unrecovered + " of " + trials + " trials, although the fault had ended.",
                        List.of(id + ".detail")));
                out.add(new Finding("HYPOTHESIS", "metastable", "A failure that outlives its trigger is typical of a self-sustaining loop: retries or a backlog keep the dependency saturated, so new attempts time out and cause more retries. If so, the dependency's backlog keeps growing after the fault ends, and capping retries (a budget) or shedding load (a breaker) should end it.",
                        List.of(id + ".detail", id + ".m.amplification")));
            }
            if (s.containsKey("amplification") && s.get("amplification").median() > 1.5) {
                out.add(new Finding("OBSERVATION", "amplification", "Run #" + r.seq() + " sent " + n(s.get("amplification").median()) + " attempts per caller request to the dependency.",
                        List.of(id + ".m.amplification")));
            }
        }
        JsonNode liveness = details.path("liveness");
        if (liveness.isObject()) {
            if (liveness.path("livelock").isObject()) {
                out.add(new Finding("OBSERVATION", "livelock", "Run #" + r.seq() + ": the state graph contains a fair cycle in which no thread makes progress — a schedule that can repeat forever.", List.of(id + ".detail")));
            }
            if (!liveness.path("complete").asBoolean()) {
                out.add(new Finding("CAVEAT", "incomplete-graph", "Run #" + r.seq() + "'s state graph is incomplete (budget reached): the absence of a livelock or starvation cycle is not established.", List.of(id + ".detail")));
            }
        }
        JsonNode explorer = details.path("explorer");
        if (explorer.isObject() && !explorer.path("exhaustive").asBoolean() && explorer.path("violations").asInt() == 0) {
            out.add(new Finding("CAVEAT", "explorer-not-exhaustive", "Run #" + r.seq() + " explored only part of the schedules and found no violation; the rest are unexamined.", List.of(id + ".detail")));
        }
        if ("stress".equals(r.params().path("mode").asString("")) && Boolean.TRUE.equals(RunMetrics.invariantHeld(r))
                && r.variant() != null && !"explore".equals(r.params().path("mode").asString(""))) {
            ScenarioCatalog.find(r.scenarioId()).flatMap(sc -> sc.descriptor().variant(r.variant()))
                    .filter(v -> Boolean.FALSE.equals(v.expectedCorrect()))
                    .ifPresent(v -> out.add(new Finding("CAVEAT", "stress-is-not-proof", "Run #" + r.seq() + " (" + r.variant()
                            + ") held the invariant under stress, but this variant is known to be incorrect: the bug simply did not manifest in these trials. Absence of a failure under stress is not evidence of correctness.",
                            List.of(id + ".inv"))));
        }
    }

    private void comparisonRules(List<Finding> out, List<RunRecord> ok, Map<String, Evidence.Item> ev) {
        for (String cid : ev.keySet()) {
            if (!cid.startsWith("C")) {
                continue;
            }
            String[] seqs = cid.substring(1).split("-");
            Optional<RunRecord> b = ok.stream().filter(r -> Integer.toString(r.seq()).equals(seqs[0])).findFirst();
            Optional<RunRecord> c = ok.stream().filter(r -> Integer.toString(r.seq()).equals(seqs[1])).findFirst();
            if (b.isEmpty() || c.isEmpty()) {
                continue;
            }
            ComparisonService.ComparisonView v = comparisons.compare(b.get(), c.get());
            v.metrics().stream().filter(ComparisonService.MetricComparison::primary).findFirst().ifPresent(mc -> {
                Comparison.Result res = mc.result();
                switch (res.verdict()) {
                    case IMPROVEMENT, REGRESSION -> out.add(new Finding("OBSERVATION", "comparison", "Run #" + seqs[1] + " vs #" + seqs[0] + ": " + res.metric() + " "
                            + res.verdict().name().toLowerCase().replace('_', ' ') + ", ratio of medians " + n(res.ratioOfMedians()) + " (95% CI " + n(res.ciLow()) + "–" + n(res.ciHigh())
                            + ", excludes 1).", List.of(cid)));
                    case NO_DIFFERENCE_DETECTED -> out.add(new Finding("CAVEAT", "no-difference", "Run #" + seqs[1] + " vs #" + seqs[0] + ": the 95% interval for " + res.metric()
                            + " (" + n(res.ciLow()) + "–" + n(res.ciHigh()) + ") contains 1 — no difference detected at this sample size. That is not evidence that the two are equal.", List.of(cid)));
                    default -> { }
                }
            });
            if (!v.environmentDifferences().isEmpty()) {
                out.add(new Finding("CAVEAT", "environment-confound", "Runs #" + seqs[0] + " and #" + seqs[1] + " ran in different environments ("
                        + v.environmentDifferences().stream().map(ComparisonService.Difference::label).distinct().toList()
                        + "); a difference between them may come from the environment rather than the variant.", List.of(cid, "R" + seqs[0] + ".env", "R" + seqs[1] + ".env")));
            }
            long others = v.parameterDifferences().stream().filter(x -> !x.field().equals("variant")).count();
            if (others > 0) {
                out.add(new Finding("CAVEAT", "multiple-changes", "Runs #" + seqs[0] + " and #" + seqs[1] + " differ in " + others
                        + " parameter(s) besides the variant; the effect cannot be attributed to a single change.", List.of(cid)));
            }
        }
    }

    private void nextExperiments(List<Finding> out, Experiment e, List<RunRecord> ok, Optional<ScenarioDescriptor> d, Map<String, Evidence.Item> ev) {
        if (ok.isEmpty()) {
            return;
        }
        long variants = ok.stream().map(RunRecord::variant).distinct().count();
        if (variants == 1) {
            out.add(new Finding("NEXT_EXPERIMENT", "single-variant", "Only one variant has been measured. Run the alternative you believe is better under the same parameters, as a CANDIDATE, so the comparison has a baseline.",
                    List.of("R" + ok.getLast().seq())));
        }
        boolean noDifference = out.stream().anyMatch(f -> f.rule().equals("no-difference"));
        boolean noisy = out.stream().anyMatch(f -> f.rule().equals("noisy-metric") || f.rule().equals("small-sample"));
        if (noDifference || noisy) {
            out.add(new Finding("NEXT_EXPERIMENT", "more-trials", "Repeat the comparison with at least twice as many trials (and a warm-up trial) on an otherwise idle machine; if the interval still contains 1, treat the variants as indistinguishable for this workload.",
                    out.stream().filter(f -> f.rule().equals("no-difference") || f.rule().equals("noisy-metric") || f.rule().equals("small-sample")).flatMap(f -> f.citations().stream()).distinct().limit(6).toList()));
        }
        if (ok.stream().anyMatch(r -> r.result().path("details").path("httpLoad").isObject())) {
            out.add(new Finding("NEXT_EXPERIMENT", "rate-sweep", "Sweep the offered rate (for example 0.5×, 0.8×, 1.0× and 1.2× of the Little's-law bound) to find the knee where latency starts to grow; a single rate cannot show where the system saturates.",
                    List.of("R" + ok.getLast().seq())));
        }
        if (ok.stream().anyMatch(r -> "stress".equals(r.params().path("mode").asString("")))
                && ok.stream().noneMatch(r -> "explore".equals(r.params().path("mode").asString("")))
                && d.map(x -> x.parameter("mode").isPresent()).orElse(false)) {
            out.add(new Finding("NEXT_EXPERIMENT", "explore-mode", "Only stress mode has been run. Run explorer mode for the same variants: it checks every interleaving at its granularity instead of hoping the bad one happens.",
                    List.of("S")));
        }
        if (ok.stream().anyMatch(r -> r.result().path("details").path("failure").isObject())) {
            out.add(new Finding("NEXT_EXPERIMENT", "headroom", "Repeat the failure run at a lower and a higher offered rate: whether retries become self-sustaining depends on (1 + retries) × rate versus the dependency's capacity, so one rate does not tell you how close to the edge you are.",
                    List.of("R" + ok.getLast().seq() + ".detail")));
        }
    }
}
