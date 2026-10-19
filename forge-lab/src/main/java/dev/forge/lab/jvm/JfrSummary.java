package dev.forge.lab.jvm;

import java.util.List;
import java.util.Map;

/**
 * What a JFR recording showed, reduced to the tables the JVM Lab renders. The raw {@code .jfr} file
 * never leaves the runner's work directory (it can be megabytes; the protocol carries summaries).
 *
 * <p>Times ({@code atMs}) are relative to the start of the recording. Frames are
 * {@code fully.qualified.Type.method}; allocation sites add {@code :line}.
 */
public record JfrSummary(
        RecordingInfo recording,
        Cpu cpu,
        Allocation allocation,
        Gc gc,
        Locks locks,
        List<Compilation> compilations,
        List<Deoptimization> deoptimizations,
        List<Marker> markers) {

    public JfrSummary {
        compilations = List.copyOf(compilations);
        deoptimizations = List.copyOf(deoptimizations);
        markers = List.copyOf(markers);
    }

    /**
     * @param eventCounts     every event in the file, by type
     * @param windowStartMs   start of the analysed window (first marker), or null if the whole
     *                        recording was analysed; CPU, allocation, GC and lock tables only count
     *                        events that started inside the window
     * @param truncatedTables names of tables that hit their row cap (the totals above them are complete)
     */
    public record RecordingInfo(double durationMs, long fileBytes, Map<String, Long> eventCounts,
                                Map<String, String> settings, Double windowStartMs, Double windowEndMs,
                                List<String> truncatedTables) {
    }

    /**
     * {@code jdk.ExecutionSample}: statistical CPU profile of threads running Java code.
     *
     * @param topSelf  methods on top of the sampled stack (where the CPU was), any code
     * @param topTotal application methods anywhere on the sampled stack (they or their callees ran);
     *                 framework frames are left out because they are on every stack
     * @param hotPaths most frequent stack tops, innermost frame first
     */
    public record Cpu(long samples, double periodMs, List<FrameRow> topSelf, List<FrameRow> topTotal,
                      List<PathRow> hotPaths) {
    }

    /**
     * @param frameTypes for self rows: how the sampled frame was executing ("Interpreted",
     *                   "JIT compiled", "Inlined", "Native") → sample count
     */
    public record FrameRow(String frame, long samples, double share, Map<String, Long> frameTypes) {
    }

    /** A distinct stack top (innermost first) and how often it was sampled. */
    public record PathRow(List<String> frames, long samples, double share) {
    }

    /**
     * {@code jdk.ObjectAllocationSample}: each sample's weight is the bytes the thread allocated since
     * its previous sample, so weights are an estimate of where allocation happened, not exact counts.
     *
     * @param discardedFirstSamples each thread's first sample in the recording is left out: having no
     *                              previous sample in this recording, its weight covers everything the
     *                              thread allocated before (observed: tens of MB credited to whatever
     *                              code happened to allocate first)
     */
    public record Allocation(long samples, long sampledBytes, long discardedFirstSamples,
                             List<AllocationClassRow> byClass, List<AllocationSiteRow> sites) {
    }

    public record AllocationClassRow(String objectClass, long weightBytes, double share, long samples) {
    }

    /**
     * @param topFrame first frame of the allocating stack (often JDK code, e.g. {@code Integer.valueOf})
     * @param appFrame first frame in application code, or null if none was in the recorded stack
     */
    public record AllocationSiteRow(String objectClass, String topFrame, String appFrame, long weightBytes,
                                    double share, long samples) {
    }

    /** {@code jdk.GarbageCollection} joined with {@code jdk.GCHeapSummary} by GC id. */
    public record Gc(String youngCollector, String oldCollector, long collections, double totalPauseMs,
                     double maxPauseMs, List<GcPause> pauses) {
    }

    public record GcPause(long gcId, String name, String cause, double atMs, double sumOfPausesMs,
                          double longestPauseMs, Long heapBeforeBytes, Long heapAfterBytes) {
    }

    public record Locks(Contention monitorEnter, Contention threadPark) {
    }

    /**
     * Only waits at or above {@code thresholdMs} are recorded by JFR, so {@code events} and
     * {@code totalMs} are lower bounds.
     */
    public record Contention(double thresholdMs, long events, double totalMs, List<ContentionRow> rows) {
    }

    /** @param lockClass monitor class ({@code synchronized}) or parked-on class (j.u.c. locks) */
    public record ContentionRow(String lockClass, String appFrame, long events, double totalMs, double maxMs,
                                List<String> stackTop) {
    }

    /**
     * @param classLoader name of the defining class loader (distinguishes freshly loaded copies of a class)
     * @param level       tier: 1–3 C1, 4 C2 (0 is the interpreter, never compiled)
     * @param endAtMs     when the compiled code became available (start + compile time)
     */
    public record Compilation(String method, String classLoader, int level, boolean osr, double atMs,
                              double durationMs, double endAtMs, long codeSize, boolean succeeded) {
    }

    public record Deoptimization(String method, String classLoader, String reason, String action, double atMs,
                                 int lineNumber) {
    }

    /** A {@link JfrMarker}: the JFR time ({@code atMs}) at which {@code System.nanoTime()} was {@code nanoTime}. */
    public record Marker(String label, double atMs, long nanoTime) {
    }
}
