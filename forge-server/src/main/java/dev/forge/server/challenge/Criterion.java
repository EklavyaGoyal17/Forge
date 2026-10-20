package dev.forge.server.challenge;

import dev.forge.lab.stats.Comparison;
import dev.forge.server.analysis.ComparisonService;
import dev.forge.server.analysis.RunMetrics;
import dev.forge.server.run.RunRecord;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A verification criterion: a predicate over an attempt's recorded runs. It reports PASS or NOT_YET
 * with the evidence it looked at (run numbers and measured values), so completion is always
 * explained by data — never by judgement.
 */
public record Criterion(String id, String description, Function<Criterion.Context, Criterion.Result> check) {

    public enum Status { PASS, NOT_YET }

    public record Result(Status status, String evidence, List<Integer> runs) {
        static Result pass(String evidence, Integer... runs) {
            return new Result(Status.PASS, evidence, List.of(runs));
        }

        static Result notYet(String evidence) {
            return new Result(Status.NOT_YET, evidence, List.of());
        }
    }

    /**
     * @param runs  successful runs of the attempt's experiment, oldest first
     * @param gate  the regression gate against the pinned baseline (empty checks when nothing is pinned)
     */
    public record Context(List<RunRecord> runs, ComparisonService comparisons, dev.forge.server.analysis.RegressionService.Report gate) {
    }

    Result evaluate(Context ctx) {
        return check.apply(ctx);
    }

    // ------------------------------------------------------------------ building blocks

    static String param(RunRecord r, String name) {
        JsonNode v = r.params().get(name);
        return v == null ? null : v.asString();
    }

    static double paramNum(RunRecord r, String name) {
        JsonNode v = r.params().get(name);
        return v == null ? Double.NaN : v.asDouble();
    }

    static Predicate<RunRecord> variant(String... ids) {
        List<String> list = List.of(ids);
        return r -> list.contains(r.variant());
    }

    static Predicate<RunRecord> notVariant(String... ids) {
        return variant(ids).negate();
    }

    static Predicate<RunRecord> paramIs(String name, String value) {
        return r -> value.equals(param(r, name));
    }

    static Optional<Double> median(RunRecord r, String metric) {
        var s = RunMetrics.summaries(r).get(metric);
        return s == null ? Optional.empty() : Optional.of(s.median());
    }

    static boolean sameParams(RunRecord a, RunRecord b, List<String> keys) {
        for (String k : keys) {
            String x = param(a, k);
            String y = param(b, k);
            if (x == null ? y != null : !x.equals(y)) {
                return false;
            }
        }
        return true;
    }

    /** A successful run matching {@code which}. */
    static Criterion ran(String id, String description, Predicate<RunRecord> which, Function<RunRecord, String> describe) {
        return new Criterion(id, description, ctx -> ctx.runs().stream().filter(which).reduce((a, b) -> b)
                .map(r -> Result.pass("Run #" + r.seq() + ": " + describe.apply(r), r.seq()))
                .orElse(Result.notYet("No matching successful run yet.")));
    }

    /**
     * A candidate run compared with a baseline run on the same workload ({@code sameKeys}): the 95%
     * bootstrap interval of candidate/baseline medians must lie entirely below {@code maxRatio}
     * (lower-is-better metrics) — or entirely above {@code minRatio} when {@code maxRatio} is NaN.
     */
    static Criterion ratio(String id, String description, Predicate<RunRecord> baseline, Predicate<RunRecord> candidate, List<String> sameKeys,
                           String metric, double maxRatio, double minRatio) {
        return new Criterion(id, description, ctx -> {
            List<String> tried = new ArrayList<>();
            List<RunRecord> cands = ctx.runs().stream().filter(candidate).sorted(Comparator.comparingInt(RunRecord::seq).reversed()).toList();
            for (RunRecord c : cands) {
                Optional<RunRecord> b = ctx.runs().stream().filter(baseline).filter(x -> x.seq() != c.seq())
                        .filter(x -> sameParams(x, c, sameKeys)).max(Comparator.comparingInt(RunRecord::seq));
                if (b.isEmpty()) {
                    tried.add("run #" + c.seq() + " has no baseline with the same " + sameKeys);
                    continue;
                }
                Optional<Comparison.Result> res = ctx.comparisons().compare(b.get(), c).metrics().stream()
                        .map(ComparisonService.MetricComparison::result).filter(m -> m.metric().equals(metric)).findFirst();
                if (res.isEmpty()) {
                    tried.add("runs #" + b.get().seq() + "/#" + c.seq() + " share no " + metric);
                    continue;
                }
                Comparison.Result m = res.get();
                String text = String.format(java.util.Locale.ROOT, "run #%d vs baseline #%d: %s ratio of medians %.4g (95%% CI %.4g–%.4g)",
                        c.seq(), b.get().seq(), metric, m.ratioOfMedians(), m.ciLow(), m.ciHigh());
                boolean ok = Double.isNaN(maxRatio) ? m.ciLow() > minRatio : m.ciHigh() < maxRatio;
                if (ok) {
                    return Result.pass(text, b.get().seq(), c.seq());
                }
                tried.add(text);
            }
            return Result.notYet(tried.isEmpty() ? "No candidate run yet." : "Not yet: " + String.join("; ", tried.subList(0, Math.min(3, tried.size()))) + ".");
        });
    }

    /** A run matching {@code which} whose invariant held in at least {@code minTrials} checked trials. */
    static Criterion invariantHeld(String id, String description, Predicate<RunRecord> which, int minTrials) {
        return new Criterion(id, description, ctx -> {
            for (RunRecord r : ctx.runs().reversed()) {
                if (!which.test(r)) {
                    continue;
                }
                JsonNode inv = r.result().path("invariant");
                int checked = inv.path("checked").asInt();
                if (Boolean.TRUE.equals(RunMetrics.invariantHeld(r)) && checked >= minTrials) {
                    return Result.pass("Run #" + r.seq() + " (" + r.variant() + "): invariant held in " + checked + " of " + checked + " checks.", r.seq());
                }
            }
            return Result.notYet("No matching run has held the invariant in at least " + minTrials + " checks yet.");
        });
    }
}
