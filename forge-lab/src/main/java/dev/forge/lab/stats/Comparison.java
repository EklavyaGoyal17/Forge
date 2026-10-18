package dev.forge.lab.stats;

import dev.forge.lab.scenario.MetricSpec.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Baseline-vs-candidate comparison of one metric. See docs/EXPERIMENT_METHODOLOGY.md §3 for the
 * reasoning behind each rule.
 */
public final class Comparison {

    public static final int MIN_RECOMMENDED_N = 5;
    public static final int BOOTSTRAP_RESAMPLES = 10_000;
    public static final double CONFIDENCE = 0.95;

    private Comparison() {
    }

    public enum Verdict {
        IMPROVEMENT,
        REGRESSION,
        NO_DIFFERENCE_DETECTED,
        /** The metric has no better/worse direction; only the interval is reported. */
        NOT_DIRECTIONAL,
        /** Both samples are constant and identical (e.g. a count that is always 0). */
        IDENTICAL
    }

    public record Result(
            String metric,
            Direction direction,
            Summary baseline,
            Summary candidate,
            double ratioOfMedians,
            double ciLow,
            double ciHigh,
            double relativeChangePercent,
            MannWhitney.Result mannWhitney,
            Verdict verdict,
            String interpretation,
            List<String> warnings) {
    }

    public static Result compare(String metric, Direction direction, double[] baseline, double[] candidate, long seed) {
        Summary sb = Summary.of(baseline);
        Summary sc = Summary.of(candidate);
        List<String> warnings = new ArrayList<>();
        if (baseline.length < MIN_RECOMMENDED_N || candidate.length < MIN_RECOMMENDED_N) {
            warnings.add("Insufficient sample: fewer than " + MIN_RECOMMENDED_N
                    + " trials in at least one run. Treat the interval as indicative only.");
        }
        if (baseline.length < 8 || candidate.length < 8) {
            warnings.add("Mann–Whitney p-value uses a normal approximation that is rough below ~8 samples per group.");
        }
        for (var entry : List.of(new Object[]{"baseline", sb}, new Object[]{"candidate", sc})) {
            Summary s = (Summary) entry[1];
            if (!Double.isNaN(s.cv()) && s.cv() > 0.10) {
                warnings.add(String.format("High variability in %s (CV = %.1f%%). More trials or a quieter machine are needed for small effects.",
                        entry[0], s.cv() * 100));
            }
        }

        boolean constantSame = sb.min() == sb.max() && sc.min() == sc.max() && sb.min() == sc.min();
        if (constantSame) {
            return new Result(metric, direction, sb, sc, 1.0, 1.0, 1.0, 0.0,
                    MannWhitney.test(baseline, candidate), Verdict.IDENTICAL,
                    "Both runs produced the same constant value in every trial.", warnings);
        }
        if (sb.median() == 0) {
            warnings.add("Baseline median is 0, so a ratio is undefined; compare absolute values instead.");
            return new Result(metric, direction, sb, sc, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    MannWhitney.test(baseline, candidate), Verdict.NOT_DIRECTIONAL,
                    String.format("Median moved from %s to %s.", fmt(sb.median()), fmt(sc.median())), warnings);
        }

        Bootstrap.Interval ci = Bootstrap.ratioOfMedians(baseline, candidate, BOOTSTRAP_RESAMPLES, CONFIDENCE, seed);
        MannWhitney.Result mw = MannWhitney.test(baseline, candidate);
        double change = (ci.estimate() - 1) * 100;

        Verdict verdict;
        if (direction == Direction.NEUTRAL) {
            verdict = Verdict.NOT_DIRECTIONAL;
        } else if (ci.low() <= 1.0 && ci.high() >= 1.0) {
            verdict = Verdict.NO_DIFFERENCE_DETECTED;
        } else {
            boolean candidateHigher = ci.low() > 1.0;
            boolean better = (direction == Direction.HIGHER_IS_BETTER) == candidateHigher;
            verdict = better ? Verdict.IMPROVEMENT : Verdict.REGRESSION;
        }

        String interpretation = switch (verdict) {
            case IMPROVEMENT, REGRESSION -> String.format(
                    "Candidate median is %.3g× baseline (%+.1f%%). The 95%% bootstrap interval [%.3g, %.3g] excludes 1.0, so at this sample size the difference is unlikely to be resampling noise. This says nothing about why it changed.",
                    ci.estimate(), change, ci.low(), ci.high());
            case NO_DIFFERENCE_DETECTED -> String.format(
                    "Candidate median is %.3g× baseline (%+.1f%%), but the 95%% bootstrap interval [%.3g, %.3g] contains 1.0. No difference was detected at this sample size — this is not evidence that the variants are equivalent.",
                    ci.estimate(), change, ci.low(), ci.high());
            case NOT_DIRECTIONAL -> String.format(
                    "Ratio of medians %.3g, 95%% interval [%.3g, %.3g]. This metric has no better/worse direction.",
                    ci.estimate(), ci.low(), ci.high());
            case IDENTICAL -> throw new IllegalStateException();
        };
        return new Result(metric, direction, sb, sc, ci.estimate(), ci.low(), ci.high(), change, mw, verdict,
                interpretation, warnings);
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.format("%.0f", v) : String.format("%.4g", v);
    }
}
