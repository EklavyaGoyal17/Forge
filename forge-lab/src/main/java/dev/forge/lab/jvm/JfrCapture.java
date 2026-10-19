package dev.forge.lab.jvm;

import jdk.jfr.Recording;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Java Flight Recorder recording of the runner JVM itself, started programmatically by a JVM Lab
 * scenario and written into the run's private work directory.
 *
 * <p>Only the events the summariser reads are enabled — not the {@code default} or {@code profile}
 * configuration — so the file stays small and the recording's own overhead is limited to what the
 * views need. The settings actually applied are returned with the summary.
 */
public final class JfrCapture implements AutoCloseable {

    /**
     * @param executionSamplePeriod how often JFR samples the stack of threads running Java code
     * @param allocationThrottle    {@code jdk.ObjectAllocationSample} rate limit, e.g. {@code "300/s"}
     * @param monitorThreshold      shortest contended {@code synchronized} enter that is recorded
     * @param parkThreshold         shortest {@code LockSupport.park} (j.u.c. locks) that is recorded
     * @param compilations          record every JIT compilation and deoptimization
     */
    public record Settings(Duration executionSamplePeriod, String allocationThrottle, Duration monitorThreshold,
                           Duration parkThreshold, boolean compilations) {

        public static Settings defaults() {
            return new Settings(Duration.ofMillis(10), "300/s", Duration.ofMillis(1), Duration.ofMillis(1), true);
        }

        public Settings withLockThresholds(Duration monitor, Duration park) {
            return new Settings(executionSamplePeriod, allocationThrottle, monitor, park, compilations);
        }

        /** Human-readable description stored with the summary. */
        public Map<String, String> describe() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("jdk.ExecutionSample#period", fmt(executionSamplePeriod));
            m.put("jdk.ObjectAllocationSample#throttle", allocationThrottle);
            m.put("jdk.JavaMonitorEnter#threshold", fmt(monitorThreshold));
            m.put("jdk.ThreadPark#threshold", fmt(parkThreshold));
            m.put("jdk.GarbageCollection", "enabled");
            m.put("jdk.Compilation#threshold", compilations ? "0 ms" : "disabled");
            return m;
        }
    }

    /** Hard ceiling on the file; JFR discards the oldest data beyond it (reported as truncation risk). */
    public static final long MAX_BYTES = 64L * 1024 * 1024;

    private final Recording recording;
    private final Path file;
    private final Settings settings;
    private Instant startedAt;
    private boolean stopped;

    private JfrCapture(Recording recording, Path file, Settings settings) {
        this.recording = recording;
        this.file = file;
        this.settings = settings;
    }

    /** Starts recording; the returned capture must be stopped (or closed) by the caller. */
    public static JfrCapture start(Path workDir, String name, Settings settings) throws IOException {
        Files.createDirectories(workDir);
        Path file = workDir.resolve(name + ".jfr");
        Recording r = new Recording();
        r.setName("forge-" + name);
        r.setToDisk(true);
        r.setMaxSize(MAX_BYTES);
        r.enable("jdk.ExecutionSample").withPeriod(settings.executionSamplePeriod());
        r.enable("jdk.ObjectAllocationSample").with("throttle", settings.allocationThrottle()).withStackTrace();
        r.enable("jdk.GarbageCollection").withoutThreshold();
        r.enable("jdk.GCHeapSummary");
        r.enable("jdk.GCConfiguration").with("period", "beginChunk");
        r.enable("jdk.JavaMonitorEnter").withThreshold(settings.monitorThreshold()).withStackTrace();
        r.enable("jdk.ThreadPark").withThreshold(settings.parkThreshold()).withStackTrace();
        if (settings.compilations()) {
            r.enable("jdk.Compilation").withoutThreshold();
            r.enable("jdk.Deoptimization").withStackTrace();
        }
        r.enable(JfrMarker.NAME);
        JfrCapture capture = new JfrCapture(r, file, settings);
        r.start();
        capture.startedAt = r.getStartTime();
        return capture;
    }

    /** Stops the recording and writes it to the work directory; idempotent. */
    public Path stop() throws IOException {
        if (!stopped) {
            stopped = true;
            recording.stop();
            recording.dump(file);
        }
        return file;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Settings settings() {
        return settings;
    }

    @Override
    public void close() {
        recording.close();
    }

    static String fmt(Duration d) {
        if (d.toNanos() % 1_000_000 == 0) {
            return d.toMillis() + " ms";
        }
        return d.toNanos() / 1_000 + " us";
    }
}
