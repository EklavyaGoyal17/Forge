package dev.forge.lab.stats;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Percentile bootstrap confidence interval for the ratio of medians of two independent samples.
 *
 * <p>Each resample draws {@code a.length} values from {@code a} and {@code b.length} values from
 * {@code b}, with replacement, and records {@code median(b*) / median(a*)}. The interval is the
 * {@code (1-confidence)/2} and {@code (1+confidence)/2} percentiles of those ratios. The random
 * stream is seeded so the same data always yields the same interval.
 *
 * <p>Assumptions worth knowing: samples are independent and identically distributed within each
 * group. Consecutive iterations inside one JVM are often autocorrelated (JIT, GC cycles), which
 * makes the interval narrower than it should be. Prefer more forks/runs over more iterations.
 */
public final class Bootstrap {

    private Bootstrap() {
    }

    public record Interval(double estimate, double low, double high, int resamples, double confidence) {
    }

    public static Interval ratioOfMedians(double[] a, double[] b, int resamples, double confidence, long seed) {
        if (a.length == 0 || b.length == 0) {
            throw new IllegalArgumentException("both samples must be non-empty");
        }
        double medA = Summary.median(a);
        double medB = Summary.median(b);
        double estimate = medB / medA;

        SplittableRandom rnd = new SplittableRandom(seed);
        double[] ratios = new double[resamples];
        double[] ra = new double[a.length];
        double[] rb = new double[b.length];
        for (int r = 0; r < resamples; r++) {
            for (int i = 0; i < a.length; i++) {
                ra[i] = a[rnd.nextInt(a.length)];
            }
            for (int i = 0; i < b.length; i++) {
                rb[i] = b[rnd.nextInt(b.length)];
            }
            Arrays.sort(ra);
            Arrays.sort(rb);
            ratios[r] = Summary.percentileSorted(rb, 50) / Summary.percentileSorted(ra, 50);
        }
        Arrays.sort(ratios);
        double alpha = (1 - confidence) / 2;
        return new Interval(estimate,
                Summary.percentileSorted(ratios, alpha * 100),
                Summary.percentileSorted(ratios, (1 - alpha) * 100),
                resamples, confidence);
    }
}
