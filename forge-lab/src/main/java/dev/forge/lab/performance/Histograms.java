package dev.forge.lab.performance;

import org.HdrHistogram.AbstractHistogram;
import org.HdrHistogram.Histogram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HdrHistogram conventions used by the performance lab.
 *
 * <p>Values are recorded in <b>microseconds</b> from 1 µs to 60 s with 3 significant digits, so
 * any reported percentile is within 0.1 % of the recorded value. Summaries are reported in
 * milliseconds rounded to the microsecond. Percentiles follow HdrHistogram's definition: the
 * value at percentile {@code p} is the smallest recorded value (bucket) such that at least
 * {@code p} % of all values are less than or equal to it.
 */
public final class Histograms {

    public static final long HIGHEST_MICROS = 60_000_000L;
    public static final int SIGNIFICANT_DIGITS = 3;

    /** Percentiles listed in a spectrum (max is reported separately). */
    public static final double[] SPECTRUM = {0, 50, 75, 90, 95, 99, 99.5, 99.9, 99.95, 99.99};

    private Histograms() {
    }

    public static Histogram newMicros() {
        return new Histogram(1, HIGHEST_MICROS, SIGNIFICANT_DIGITS);
    }

    public static long toMicros(long nanos) {
        long us = Math.round(nanos / 1_000.0);
        return Math.max(1, Math.min(HIGHEST_MICROS, us));
    }

    public static double ms(double micros) {
        return Math.round(micros) / 1_000.0;
    }

    /**
     * count, mean, min, p50, p90, p95, p99, p99.9 and max in milliseconds; only {@code count} when
     * the histogram is empty.
     */
    public static Map<String, Object> summary(AbstractHistogram h) {
        Map<String, Object> m = new LinkedHashMap<>();
        long count = h.getTotalCount();
        m.put("count", count);
        if (count == 0) {
            return m;
        }
        m.put("meanMs", Math.round(h.getMean()) / 1_000.0);
        m.put("minMs", ms(h.getMinValue()));
        m.put("p50Ms", ms(h.getValueAtPercentile(50)));
        m.put("p90Ms", ms(h.getValueAtPercentile(90)));
        m.put("p95Ms", ms(h.getValueAtPercentile(95)));
        m.put("p99Ms", ms(h.getValueAtPercentile(99)));
        m.put("p999Ms", ms(h.getValueAtPercentile(99.9)));
        m.put("maxMs", ms(h.getMaxValue()));
        return m;
    }

    /**
     * Value at each percentile of {@link #SPECTRUM}. {@code resolvable} is false when the sample is
     * too small to distinguish that percentile from the maximum (fewer than {@code 1/(1-p)} values).
     */
    public static List<Map<String, Object>> spectrum(AbstractHistogram h) {
        List<Map<String, Object>> out = new ArrayList<>();
        long count = h.getTotalCount();
        if (count == 0) {
            return out;
        }
        for (double p : SPECTRUM) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("percentile", p);
            point.put("valueMs", ms(p == 0 ? h.getMinValue() : h.getValueAtPercentile(p)));
            point.put("resolvable", count * (100.0 - p) / 100.0 >= 1.0 - 1e-9);
            out.add(point);
        }
        return out;
    }
}
