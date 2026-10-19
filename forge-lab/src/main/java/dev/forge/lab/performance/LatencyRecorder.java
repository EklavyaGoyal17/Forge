package dev.forge.lab.performance;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.IntCountsHistogram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side measurements of one trial of an open-model load.
 *
 * <p>Every request has three timestamps: when it was <i>intended</i> to be sent (from the
 * {@link ArrivalSchedule}), when it was actually <i>sent</i>, and when it <i>completed</i>. The
 * reported latency is {@code completed − intended}: if the generator could not send on time
 * (no free connection, a stalled thread), that delay is part of what a user arriving at the
 * intended time would have experienced. Measuring from {@code sent} instead silently drops the
 * waiting time of exactly the requests that were hurt most — the error Gil Tene named
 * <i>coordinated omission</i>. Both are recorded so the difference is visible.
 *
 * <p>Thread-safe: completions are recorded from HTTP client callback threads.
 */
public final class LatencyRecorder {

    public enum Outcome {
        /** HTTP 200. */
        OK,
        /** A response with another status. */
        HTTP_ERROR,
        /** Transport failure (connection refused/reset, ...). */
        FAILED,
        /** No response within the request timeout, measured from the intended send time. */
        TIMEOUT
    }

    private final long startNanos;
    private final long measureStartNanos;
    private final long measureEndNanos;
    private final long windowNanos;

    // measured requests only (intended time inside the sustained phase)
    private final Histogram corrected = Histograms.newMicros();
    private final Histogram uncorrected = Histograms.newMicros();
    private final Histogram timerLag = Histograms.newMicros();
    private long offered;
    private long sent;
    private long ok;
    private long httpErrors;
    private long failures;
    private long timeouts;
    private long okCompletedInWindow;

    // all requests, by time window (for the latency-over-time series)
    private final Window[] windows;

    private static final class Window {
        long offered;
        long completedOk;
        long errors;
        IntCountsHistogram latency;
    }

    public LatencyRecorder(long startNanos, long warmupNanos, long measureNanos, long trailingNanos, long windowNanos) {
        if (windowNanos <= 0 || measureNanos <= 0 || warmupNanos < 0 || trailingNanos < 0) {
            throw new IllegalArgumentException("invalid recorder spans");
        }
        this.startNanos = startNanos;
        this.measureStartNanos = startNanos + warmupNanos;
        this.measureEndNanos = measureStartNanos + measureNanos;
        this.windowNanos = windowNanos;
        long span = warmupNanos + measureNanos + trailingNanos;
        this.windows = new Window[(int) Math.min(10_000, (span + windowNanos - 1) / windowNanos)];
    }

    public boolean isMeasured(long intendedNanos) {
        return intendedNanos - measureStartNanos >= 0 && intendedNanos - measureEndNanos < 0;
    }

    /** How late the generator's timer woke up for a request it had to wait for. */
    public synchronized void timerLag(long intendedNanos, long wokeNanos) {
        if (isMeasured(intendedNanos)) {
            timerLag.recordValue(Histograms.toMicros(Math.max(0, wokeNanos - intendedNanos)));
        }
    }

    /**
     * @param sentNanos time the request was handed to the transport, or {@code -1} if it never was
     *                  (it timed out waiting for a connection).
     */
    public synchronized void record(long intendedNanos, long sentNanos, long completedNanos, Outcome outcome) {
        Window w = window(intendedNanos);
        long latency = Math.max(0, completedNanos - intendedNanos);
        if (w != null) {
            w.offered++;
            if (w.latency == null) {
                w.latency = new IntCountsHistogram(1, Histograms.HIGHEST_MICROS, 2);
            }
            w.latency.recordValue(Histograms.toMicros(latency));
        }
        Window done = window(completedNanos);
        if (done != null) {
            if (outcome == Outcome.OK) {
                done.completedOk++;
            } else {
                done.errors++;
            }
        }
        if (outcome == Outcome.OK && completedNanos - measureStartNanos >= 0 && completedNanos - measureEndNanos < 0) {
            okCompletedInWindow++;
        }
        if (!isMeasured(intendedNanos)) {
            return;
        }
        offered++;
        corrected.recordValue(Histograms.toMicros(latency));
        if (sentNanos >= 0) {
            sent++;
            uncorrected.recordValue(Histograms.toMicros(Math.max(0, completedNanos - sentNanos)));
        }
        switch (outcome) {
            case OK -> ok++;
            case HTTP_ERROR -> httpErrors++;
            case FAILED -> failures++;
            case TIMEOUT -> timeouts++;
        }
    }

    private Window window(long nanos) {
        long offset = nanos - startNanos;
        if (offset < 0 || windows.length == 0) {
            return null;
        }
        int i = (int) Math.min(windows.length - 1, offset / windowNanos);
        if (windows[i] == null) {
            windows[i] = new Window();
        }
        return windows[i];
    }

    /** Latency from the intended send time (coordinated-omission corrected), measured requests. */
    public synchronized Histogram corrected() {
        return corrected.copy();
    }

    /** Latency from the actual send time (what a naive generator reports), measured requests that were sent. */
    public synchronized Histogram uncorrected() {
        return uncorrected.copy();
    }

    public synchronized Histogram timerLag() {
        return timerLag.copy();
    }

    public record Counts(long offered, long sent, long ok, long httpErrors, long failures, long timeouts, long okCompletedInWindow) {
        public long errors() {
            return httpErrors + failures + timeouts;
        }
    }

    public synchronized Counts counts() {
        return new Counts(offered, sent, ok, httpErrors, failures, timeouts, okCompletedInWindow);
    }

    public double achievedPerSecond() {
        return counts().okCompletedInWindow() / ((measureEndNanos - measureStartNanos) / 1e9);
    }

    /**
     * One point per time window from the start of the trial: requests offered (by intended time),
     * completed OK and failed (by completion time), and p50/p99/max latency of the requests
     * intended in that window. Trailing empty windows are dropped.
     */
    public synchronized List<Map<String, Object>> series() {
        int last = -1;
        for (int i = 0; i < windows.length; i++) {
            if (windows[i] != null) {
                last = i;
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i <= last; i++) {
            Window w = windows[i];
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("tMs", i * windowNanos / 1_000_000.0);
            p.put("offered", w == null ? 0 : w.offered);
            p.put("completed", w == null ? 0 : w.completedOk);
            p.put("errors", w == null ? 0 : w.errors);
            if (w != null && w.latency != null && w.latency.getTotalCount() > 0) {
                p.put("p50Ms", Histograms.ms(w.latency.getValueAtPercentile(50)));
                p.put("p99Ms", Histograms.ms(w.latency.getValueAtPercentile(99)));
                p.put("maxMs", Histograms.ms(w.latency.getMaxValue()));
            }
            out.add(p);
        }
        return out;
    }

    public long windowNanos() {
        return windowNanos;
    }

    public long measureStartOffsetNanos() {
        return measureStartNanos - startNanos;
    }

    public long measureEndOffsetNanos() {
        return measureEndNanos - startNanos;
    }
}
