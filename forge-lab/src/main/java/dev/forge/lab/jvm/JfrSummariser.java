package dev.forge.lab.jvm;

import dev.forge.lab.jvm.JfrSummary.Allocation;
import dev.forge.lab.jvm.JfrSummary.AllocationClassRow;
import dev.forge.lab.jvm.JfrSummary.AllocationSiteRow;
import dev.forge.lab.jvm.JfrSummary.Compilation;
import dev.forge.lab.jvm.JfrSummary.Contention;
import dev.forge.lab.jvm.JfrSummary.ContentionRow;
import dev.forge.lab.jvm.JfrSummary.Cpu;
import dev.forge.lab.jvm.JfrSummary.Deoptimization;
import dev.forge.lab.jvm.JfrSummary.FrameRow;
import dev.forge.lab.jvm.JfrSummary.Gc;
import dev.forge.lab.jvm.JfrSummary.GcPause;
import dev.forge.lab.jvm.JfrSummary.Locks;
import dev.forge.lab.jvm.JfrSummary.Marker;
import dev.forge.lab.jvm.JfrSummary.PathRow;
import dev.forge.lab.jvm.JfrSummary.RecordingInfo;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedClassLoader;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Reads a {@code .jfr} file with {@link RecordingFile} and reduces it to a {@link JfrSummary}.
 * Deterministic for a given file: ties are broken by name, so the same recording always yields the
 * same tables.
 */
public final class JfrSummariser {

    /**
     * @param appPackagePrefix frames whose class starts with this are "application code": used for
     *                         allocation/lock attribution and to select which compilations to list
     * @param topRows          rows kept per table
     * @param pathDepth        frames kept per hot path and per lock stack
     * @param maxPauses        GC pauses listed individually (totals always cover all of them)
     * @param maxCompilations  compilations/deoptimizations listed
     * @param betweenMarkers   when the file holds at least two {@link JfrMarker}s, count CPU,
     *                         allocation, GC and lock events only between the first and the last one
     *                         (the measured region), leaving out JFR start-up and the stop/dump code
     */
    public record Options(String appPackagePrefix, int topRows, int pathDepth, int maxPauses, int maxCompilations,
                          boolean betweenMarkers) {
        public static Options forPackage(String prefix) {
            return new Options(prefix, 12, 6, 400, 400, true);
        }

        public Options withMaxPauses(int max) {
            return new Options(appPackagePrefix, topRows, pathDepth, max, maxCompilations, betweenMarkers);
        }
    }

    private static final Pattern HIDDEN_CLASS_SUFFIX = Pattern.compile("([./])0x[0-9a-fA-F]+");

    private JfrSummariser() {
    }

    /**
     * @param origin time zero for {@code atMs} values; null = the earliest event in the file
     */
    public static JfrSummary summarise(Path file, Instant origin, JfrCapture.Settings settings, Options opt)
            throws IOException {
        List<RecordedEvent> events = RecordingFile.readAllEvents(file);
        Instant first = null;
        Instant last = null;
        for (RecordedEvent e : events) {
            if (first == null || e.getStartTime().isBefore(first)) {
                first = e.getStartTime();
            }
            if (last == null || e.getEndTime().isAfter(last)) {
                last = e.getEndTime();
            }
        }
        Instant t0 = origin != null ? origin : first != null ? first : Instant.EPOCH;
        Instant windowStart = null;
        Instant windowEnd = null;
        if (opt.betweenMarkers()) {
            List<Instant> marks = events.stream().filter(e -> e.getEventType().getName().equals(JfrMarker.NAME))
                    .map(RecordedEvent::getStartTime).sorted().toList();
            if (marks.size() >= 2) {
                windowStart = marks.getFirst();
                windowEnd = marks.getLast();
            }
        }
        Acc acc = new Acc(t0, opt, windowStart, windowEnd);
        for (RecordedEvent e : events) {
            acc.accept(e);
        }
        double durationMs = last == null ? 0 : Math.max(0, ms(Duration.between(t0, last)));
        RecordingInfo info = new RecordingInfo(durationMs, Files.size(file), acc.counts,
                settings == null ? Map.of() : settings.describe(),
                windowStart == null ? null : ms(Duration.between(t0, windowStart)),
                windowEnd == null ? null : ms(Duration.between(t0, windowEnd)), acc.truncated);
        Gc gc = acc.gc();
        Cpu cpu = acc.cpu(settings);
        Allocation alloc = acc.allocation();
        Locks locks = new Locks(acc.monitor.build("monitorEnter", settings == null ? 0 : ms(settings.monitorThreshold())),
                acc.park.build("threadPark", settings == null ? 0 : ms(settings.parkThreshold())));
        acc.compilations.sort(Comparator.comparingDouble(Compilation::atMs).thenComparing(Compilation::method));
        acc.deopts.sort(Comparator.comparingDouble(Deoptimization::atMs).thenComparing(Deoptimization::method));
        acc.markers.sort(Comparator.comparingDouble(Marker::atMs));
        List<Compilation> comps = cap(acc.compilations, opt.maxCompilations(), "compilations", acc.truncated);
        List<Deoptimization> deopts = cap(acc.deopts, opt.maxCompilations(), "deoptimizations", acc.truncated);
        return new JfrSummary(info, cpu, alloc, gc, locks, comps, deopts, acc.markers);
    }

    /** Converts a JFR-relative time to the runner's {@code System.nanoTime()} using the two closest markers. */
    public static long toNanoTime(double atMs, List<Marker> markers) {
        if (markers.isEmpty()) {
            throw new IllegalArgumentException("no markers to correlate clocks");
        }
        if (markers.size() == 1) {
            Marker m = markers.getFirst();
            return m.nanoTime() + Math.round((atMs - m.atMs()) * 1e6);
        }
        Marker a = markers.getFirst();
        Marker b = markers.get(1);
        for (int i = 1; i < markers.size(); i++) {
            b = markers.get(i);
            if (b.atMs() >= atMs) {
                break;
            }
            a = b;
        }
        if (b == a || b.atMs() == a.atMs()) {
            return a.nanoTime() + Math.round((atMs - a.atMs()) * 1e6);
        }
        double scale = (b.nanoTime() - a.nanoTime()) / ((b.atMs() - a.atMs()) * 1e6);
        return a.nanoTime() + Math.round((atMs - a.atMs()) * 1e6 * scale);
    }

    // ----------------------------------------------------------------------------------------

    private static final class Acc {
        final Instant t0;
        final Options opt;
        final Map<String, Long> counts = new TreeMap<>();
        final List<String> truncated = new ArrayList<>();

        long cpuSamples;
        final Map<String, long[]> self = new HashMap<>();
        final Map<String, Map<String, Long>> selfTypes = new HashMap<>();
        final Map<String, long[]> total = new HashMap<>();
        final Map<List<String>, long[]> paths = new HashMap<>();

        long allocSamples;
        long allocBytes;
        long allocDiscarded;
        final Set<Long> allocThreadsSeen = new java.util.HashSet<>();
        final Map<String, long[]> allocByClass = new HashMap<>();
        final Map<List<String>, long[]> allocBySite = new HashMap<>();

        String youngCollector;
        String oldCollector;
        final List<GcPause> pauses = new ArrayList<>();
        final Map<Long, Long> heapBefore = new HashMap<>();
        final Map<Long, Long> heapAfter = new HashMap<>();

        final ContentionAcc monitor = new ContentionAcc();
        final ContentionAcc park = new ContentionAcc();

        final List<Compilation> compilations = new ArrayList<>();
        final List<Deoptimization> deopts = new ArrayList<>();
        final List<Marker> markers = new ArrayList<>();

        final Instant windowStart;
        final Instant windowEnd;

        Acc(Instant t0, Options opt, Instant windowStart, Instant windowEnd) {
            this.t0 = t0;
            this.opt = opt;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
        }

        boolean inWindow(RecordedEvent e) {
            return windowStart == null
                    || (!e.getStartTime().isBefore(windowStart) && !e.getStartTime().isAfter(windowEnd));
        }

        double at(RecordedEvent e) {
            return ms(Duration.between(t0, e.getStartTime()));
        }

        void accept(RecordedEvent e) {
            String type = e.getEventType().getName();
            counts.merge(type, 1L, Long::sum);
            boolean windowed = switch (type) {
                case "jdk.ExecutionSample", "jdk.ObjectAllocationSample", "jdk.GarbageCollection",
                     "jdk.JavaMonitorEnter", "jdk.ThreadPark" -> true;
                default -> false;
            };
            if (windowed && !inWindow(e)) {
                return;
            }
            switch (type) {
                case "jdk.ExecutionSample" -> cpuSample(e);
                case "jdk.ObjectAllocationSample" -> allocation(e);
                case "jdk.GarbageCollection" -> pauses.add(new GcPause(e.getLong("gcId"), e.getString("name"),
                        e.getString("cause"), at(e), ms(e.getDuration("sumOfPauses")),
                        ms(e.getDuration("longestPause")), null, null));
                case "jdk.GCHeapSummary" -> {
                    long id = e.getLong("gcId");
                    long used = e.getLong("heapUsed");
                    if ("Before GC".equals(e.getString("when"))) {
                        heapBefore.put(id, used);
                    } else {
                        heapAfter.put(id, used);
                    }
                }
                case "jdk.GCConfiguration" -> {
                    youngCollector = e.getString("youngCollector");
                    oldCollector = e.getString("oldCollector");
                }
                case "jdk.JavaMonitorEnter" -> monitor.add(className(e.getClass("monitorClass")),
                        appFrame(e.getStackTrace(), false), ms(e.getDuration()), stackTop(e.getStackTrace()));
                case "jdk.ThreadPark" -> park.add(className(e.getClass("parkedClass")),
                        appFrame(e.getStackTrace(), false), ms(e.getDuration()), stackTop(e.getStackTrace()));
                case "jdk.Compilation" -> compilation(e);
                case "jdk.Deoptimization" -> deoptimization(e);
                case JfrMarker.NAME -> markers.add(new Marker(e.getString("label"), at(e), e.getLong("nanoTime")));
                default -> { }
            }
        }

        void cpuSample(RecordedEvent e) {
            RecordedStackTrace st = e.getStackTrace();
            if (st == null || st.getFrames().isEmpty()) {
                return;
            }
            cpuSamples++;
            List<RecordedFrame> frames = st.getFrames();
            String top = frame(frames.getFirst(), false);
            self.computeIfAbsent(top, k -> new long[1])[0]++;
            selfTypes.computeIfAbsent(top, k -> new TreeMap<>()).merge(frameType(frames.getFirst()), 1L, Long::sum);
            Set<String> seen = new LinkedHashSet<>();
            for (RecordedFrame f : frames) {
                if (f.isJavaFrame() && f.getMethod().getType().getName().startsWith(opt.appPackagePrefix())) {
                    seen.add(frame(f, false));
                }
            }
            for (String f : seen) {
                total.computeIfAbsent(f, k -> new long[1])[0]++;
            }
            List<String> path = new ArrayList<>();
            for (int i = 0; i < frames.size() && path.size() < opt.pathDepth(); i++) {
                path.add(frame(frames.get(i), false));
            }
            paths.computeIfAbsent(List.copyOf(path), k -> new long[1])[0]++;
        }

        void allocation(RecordedEvent e) {
            var thread = e.getThread();
            long threadKey = thread == null ? -1 : thread.getId();
            if (allocThreadsSeen.add(threadKey)) {
                allocDiscarded++;                    // weight reaches back before the recording
                return;
            }
            long weight = e.getLong("weight");
            String cls = className(e.getClass("objectClass"));
            allocSamples++;
            allocBytes += weight;
            long[] c = allocByClass.computeIfAbsent(cls, k -> new long[2]);
            c[0] += weight;
            c[1]++;
            RecordedStackTrace st = e.getStackTrace();
            String top = st == null || st.getFrames().isEmpty() ? "(no stack)" : frame(st.getFrames().getFirst(), true);
            String app = appFrame(st, true);
            List<String> key = java.util.Arrays.asList(cls, top, app);
            long[] s = allocBySite.computeIfAbsent(key, k -> new long[2]);
            s[0] += weight;
            s[1]++;
        }

        void compilation(RecordedEvent e) {
            RecordedMethod m = e.getValue("method");
            if (m == null || !m.getType().getName().startsWith(opt.appPackagePrefix())) {
                return;
            }
            boolean ok = e.hasField("succeded") ? e.getBoolean("succeded")
                    : !e.hasField("succeeded") || e.getBoolean("succeeded");
            double start = at(e);
            double dur = ms(e.getDuration());
            compilations.add(new Compilation(method(m), loaderName(m.getType()), e.getInt("compileLevel"),
                    e.getBoolean("isOsr"), start, dur, start + dur, e.getLong("codeSize"), ok));
        }

        void deoptimization(RecordedEvent e) {
            RecordedMethod m = e.getValue("method");
            if (m == null || !m.getType().getName().startsWith(opt.appPackagePrefix())) {
                return;
            }
            deopts.add(new Deoptimization(method(m), loaderName(m.getType()), e.getString("reason"),
                    e.getString("action"), at(e), e.hasField("lineNumber") ? e.getInt("lineNumber") : -1));
        }

        String appFrame(RecordedStackTrace st, boolean withLine) {
            if (st == null) {
                return null;
            }
            for (RecordedFrame f : st.getFrames()) {
                if (f.isJavaFrame() && f.getMethod().getType().getName().startsWith(opt.appPackagePrefix())) {
                    return frame(f, withLine);
                }
            }
            return null;
        }

        List<String> stackTop(RecordedStackTrace st) {
            if (st == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (RecordedFrame f : st.getFrames()) {
                if (out.size() >= opt.pathDepth()) {
                    break;
                }
                out.add(frame(f, true));
            }
            return out;
        }

        Cpu cpu(JfrCapture.Settings settings) {
            List<FrameRow> selfRows = top(self, "cpu.topSelf").stream()
                    .map(en -> new FrameRow(en.getKey(), en.getValue()[0], share(en.getValue()[0], cpuSamples),
                            selfTypes.get(en.getKey())))
                    .toList();
            List<FrameRow> totalRows = top(total, "cpu.topTotal").stream()
                    .map(en -> new FrameRow(en.getKey(), en.getValue()[0], share(en.getValue()[0], cpuSamples), null))
                    .toList();
            List<PathRow> pathRows = top(paths, "cpu.hotPaths").stream()
                    .map(en -> new PathRow(en.getKey(), en.getValue()[0], share(en.getValue()[0], cpuSamples)))
                    .toList();
            return new Cpu(cpuSamples, settings == null ? 0 : ms(settings.executionSamplePeriod()),
                    selfRows, totalRows, pathRows);
        }

        Allocation allocation() {
            List<AllocationClassRow> byClass = top(allocByClass, "allocation.byClass").stream()
                    .map(en -> new AllocationClassRow(en.getKey(), en.getValue()[0], share(en.getValue()[0], allocBytes),
                            en.getValue()[1]))
                    .toList();
            List<AllocationSiteRow> sites = top(allocBySite, "allocation.sites").stream()
                    .map(en -> new AllocationSiteRow(en.getKey().get(0), en.getKey().get(1), en.getKey().get(2),
                            en.getValue()[0], share(en.getValue()[0], allocBytes), en.getValue()[1]))
                    .toList();
            return new Allocation(allocSamples, allocBytes, allocDiscarded, byClass, sites);
        }

        Gc gc() {
            pauses.sort(Comparator.comparingLong(GcPause::gcId));
            double totalPause = 0;
            double maxPause = 0;
            List<GcPause> joined = new ArrayList<>(pauses.size());
            for (GcPause p : pauses) {
                totalPause += p.sumOfPausesMs();
                maxPause = Math.max(maxPause, p.longestPauseMs());
                joined.add(new GcPause(p.gcId(), p.name(), p.cause(), p.atMs(), p.sumOfPausesMs(), p.longestPauseMs(),
                        heapBefore.get(p.gcId()), heapAfter.get(p.gcId())));
            }
            return new Gc(youngCollector, oldCollector, pauses.size(), totalPause, maxPause,
                    cap(joined, opt.maxPauses(), "gc.pauses", truncated));
        }

        /** Sorted by weight descending, then key, capped at topRows; records truncation. */
        <K> List<Map.Entry<K, long[]>> top(Map<K, long[]> m, String table) {
            List<Map.Entry<K, long[]>> rows = new ArrayList<>(m.entrySet());
            rows.sort(Comparator.<Map.Entry<K, long[]>>comparingLong(en -> -en.getValue()[0])
                    .thenComparing(en -> String.valueOf(en.getKey())));
            if (rows.size() > opt.topRows()) {
                truncated.add(table);
                return rows.subList(0, opt.topRows());
            }
            return rows;
        }

        final class ContentionAcc {
            long events;
            double totalMs;
            final Map<List<String>, double[]> rows = new HashMap<>();
            final Map<List<String>, List<String>> stacks = new HashMap<>();

            void add(String lockClass, String appFrame, double ms, List<String> stack) {
                events++;
                totalMs += ms;
                List<String> key = java.util.Arrays.asList(lockClass, appFrame);
                double[] r = rows.computeIfAbsent(key, k -> new double[3]);
                r[0]++;
                r[1] += ms;
                r[2] = Math.max(r[2], ms);
                stacks.putIfAbsent(key, stack);
            }

            Contention build(String table, double thresholdMs) {
                List<Map.Entry<List<String>, double[]>> sorted = new ArrayList<>(rows.entrySet());
                sorted.sort(Comparator.<Map.Entry<List<String>, double[]>>comparingDouble(en -> -en.getValue()[1])
                        .thenComparing(en -> String.valueOf(en.getKey())));
                if (sorted.size() > opt.topRows()) {
                    truncated.add("locks." + table);
                    sorted = sorted.subList(0, opt.topRows());
                }
                List<ContentionRow> out = sorted.stream()
                        .map(en -> new ContentionRow(en.getKey().get(0), en.getKey().get(1), (long) en.getValue()[0],
                                en.getValue()[1], en.getValue()[2], stacks.get(en.getKey())))
                        .toList();
                return new Contention(thresholdMs, events, totalMs, out);
            }
        }
    }

    private static <T> List<T> cap(List<T> list, int max, String table, List<String> truncated) {
        if (list.size() > max) {
            truncated.add(table);
            return List.copyOf(list.subList(0, max));
        }
        return List.copyOf(list);
    }

    static String frame(RecordedFrame f, boolean withLine) {
        String s = method(f.getMethod());
        if (withLine && f.getLineNumber() > 0) {
            s += ":" + f.getLineNumber();
        }
        return s;
    }

    static String method(RecordedMethod m) {
        return className(m.getType()) + "." + m.getName();
    }

    /** Class name with the per-JVM address suffix of hidden classes (lambdas) removed. */
    static String className(RecordedClass c) {
        if (c == null) {
            return "(unknown)";
        }
        return readableType(HIDDEN_CLASS_SUFFIX.matcher(c.getName()).replaceAll(""));
    }

    /** {@code [B} → {@code byte[]}, {@code [Ljava.lang.Integer;} → {@code java.lang.Integer[]}. */
    static String readableType(String name) {
        int dims = 0;
        while (dims < name.length() && name.charAt(dims) == '[') {
            dims++;
        }
        if (dims == 0) {
            return name;
        }
        String element = name.substring(dims);
        String base = switch (element) {
            case "Z" -> "boolean";
            case "B" -> "byte";
            case "C" -> "char";
            case "S" -> "short";
            case "I" -> "int";
            case "J" -> "long";
            case "F" -> "float";
            case "D" -> "double";
            default -> element.startsWith("L") && element.endsWith(";")
                    ? element.substring(1, element.length() - 1) : element;
        };
        return base + "[]".repeat(dims);
    }

    private static String loaderName(RecordedClass c) {
        RecordedClassLoader l = c.getClassLoader();
        if (l == null) {
            return "bootstrap";
        }
        return l.getName() != null ? l.getName() : className(l.getType());
    }

    private static String frameType(RecordedFrame f) {
        String t = f.getType();
        return t == null ? "unknown" : t;
    }

    private static double share(long part, long whole) {
        return whole == 0 ? 0 : (double) part / whole;
    }

    static double ms(Duration d) {
        return d == null ? 0 : d.toNanos() / 1e6;
    }
}
