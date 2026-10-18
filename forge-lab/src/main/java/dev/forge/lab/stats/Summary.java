package dev.forge.lab.stats;

import java.util.Arrays;

/**
 * Descriptive statistics of one sample.
 *
 * <p>Percentiles use linear interpolation between closest ranks (Hyndman &amp; Fan type 7, the
 * default in R and NumPy). The standard deviation is the sample standard deviation (n − 1).
 * {@code cv} is the coefficient of variation (stddev / mean), {@code NaN} when the mean is 0.
 */
public record Summary(int n, double mean, double stddev, double min, double max,
                      double median, double p90, double p95, double p99, double cv) {

    public static Summary of(double[] values) {
        if (values.length == 0) {
            return new Summary(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double mean = mean(sorted);
        double sd = stddev(sorted, mean);
        return new Summary(sorted.length, mean, sd, sorted[0], sorted[sorted.length - 1],
                percentileSorted(sorted, 50), percentileSorted(sorted, 90),
                percentileSorted(sorted, 95), percentileSorted(sorted, 99),
                mean == 0 ? Double.NaN : sd / Math.abs(mean));
    }

    public static double mean(double[] v) {
        // Kahan summation: timing samples can span many orders of magnitude.
        double sum = 0;
        double c = 0;
        for (double x : v) {
            double y = x - c;
            double t = sum + y;
            c = (t - sum) - y;
            sum = t;
        }
        return sum / v.length;
    }

    static double stddev(double[] v, double mean) {
        if (v.length < 2) {
            return 0;
        }
        double ss = 0;
        for (double x : v) {
            ss += (x - mean) * (x - mean);
        }
        return Math.sqrt(ss / (v.length - 1));
    }

    /** @param p percentile in [0, 100]; {@code sorted} must be ascending and non-empty. */
    public static double percentileSorted(double[] sorted, double p) {
        if (p < 0 || p > 100) {
            throw new IllegalArgumentException("percentile must be in [0, 100]");
        }
        if (sorted.length == 1) {
            return sorted[0];
        }
        double h = (sorted.length - 1) * p / 100.0;
        int lo = (int) Math.floor(h);
        int hi = (int) Math.ceil(h);
        return sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo]);
    }

    public static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return percentileSorted(sorted, 50);
    }
}
