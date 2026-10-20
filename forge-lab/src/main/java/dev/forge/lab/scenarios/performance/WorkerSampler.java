package dev.forge.lab.scenarios.performance;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/**
 * A wall-clock sampler of the target service's worker threads: every period it records each
 * worker's state and its top application-relevant frame. Unlike JFR's execution samples, which only
 * see threads that are running on a CPU, this also sees threads that are waiting — which is where the
 * time goes when a pool is blocked on a downstream call.
 *
 * <p>Only platform threads are visible to {@link ThreadMXBean}; virtual-thread workers are not
 * sampled. Sampling costs one {@code getThreadInfo} call per period and is included in what the run
 * measures.
 */
final class WorkerSampler implements AutoCloseable {

    static final String PREFIX = "target-worker-";
    private static final int DEPTH = 8;

    private final ThreadMXBean mx = ManagementFactory.getThreadMXBean();
    private final long periodNanos;
    private final long fromNanos;
    private final long toNanos;
    private final Thread thread;
    private volatile boolean running = true;
    private final Map<Thread.State, Long> states = new EnumMap<>(Thread.State.class);
    private final Map<String, Long> frames = new HashMap<>();
    private long samples;
    private long threadSamples;
    private long idleSamples;
    private long blockedSamples;
    private final boolean sampleThreads;
    private final java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
    private long cpuStart = -1;
    private long cpuEnd = -1;
    private long wallStart;
    private long wallEnd;

    /**
     * Samples between {@code fromNanos} and {@code toNanos} ({@link System#nanoTime()}). Process CPU
     * time is always recorded at the window's edges; worker states only when {@code sampleThreads}.
     */
    WorkerSampler(long periodNanos, long fromNanos, long toNanos, boolean sampleThreads) {
        this.periodNanos = periodNanos;
        this.fromNanos = fromNanos;
        this.toNanos = toNanos;
        this.sampleThreads = sampleThreads;
        this.thread = new Thread(this::loop, "forge-worker-sampler");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void loop() {
        long next = System.nanoTime();
        while (running) {
            next += periodNanos;
            long wait;
            while ((wait = next - System.nanoTime()) > 0 && running) {
                LockSupport.parkNanos(wait);
            }
            long now = System.nanoTime();
            if (now - toNanos >= 0) {
                return;
            }
            if (now - fromNanos >= 0) {
                long cpu = processCpuNanos();
                synchronized (this) {
                    if (cpuStart < 0) {
                        cpuStart = cpu;
                        wallStart = now;
                    }
                    cpuEnd = cpu;
                    wallEnd = now;
                }
                if (sampleThreads) {
                    sample();
                }
            }
        }
    }

    private void sample() {
        ThreadInfo[] all = mx.getThreadInfo(mx.getAllThreadIds(), 0);
        List<Long> ids = new ArrayList<>();
        for (ThreadInfo i : all) {
            if (i != null && i.getThreadName().startsWith(PREFIX)) {
                ids.add(i.getThreadId());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        ThreadInfo[] workers = mx.getThreadInfo(ids.stream().mapToLong(Long::longValue).toArray(), DEPTH);
        synchronized (this) {
            samples++;
            for (ThreadInfo w : workers) {
                if (w == null) {
                    continue;
                }
                threadSamples++;
                String frame = topFrame(w.getStackTrace());
                states.merge(w.getThreadState(), 1L, Long::sum);
                frames.merge(frame, 1L, Long::sum);
                if (frame.startsWith("idle")) {
                    idleSamples++;
                } else if (w.getThreadState() != Thread.State.RUNNABLE) {
                    blockedSamples++;   // inside a request, but not running: sleeping, parked, blocked
                }
            }
        }
    }

    /**
     * What the thread is doing: the first public-API frame (JDK internals and native {@code …0}
     * helpers skipped), followed by the FORGE frame that called it — e.g.
     * {@code Thread.sleep ← TargetService$Call.blockingDownstreamCall}. A pool thread with no FORGE
     * frame waiting in {@code getTask} is idle, waiting for a request.
     */
    static String topFrame(StackTraceElement[] stack) {
        String api = null;
        String app = null;
        boolean idle = false;
        for (StackTraceElement e : stack) {
            String c = e.getClassName();
            if (api == null && !c.startsWith("jdk.internal.") && !c.startsWith("sun.") && !e.getMethodName().endsWith("0")) {
                api = shortClass(c) + "." + e.getMethodName();
            }
            if (app == null && c.startsWith("dev.forge.")) {
                app = shortClass(c) + "." + e.getMethodName();
            }
            idle |= e.getMethodName().equals("getTask") && c.equals("java.util.concurrent.ThreadPoolExecutor");
        }
        if (app == null && idle) {
            return "idle: waiting for a request (ThreadPoolExecutor.getTask)";
        }
        if (api == null) {
            return stack.length == 0 ? "(no frames)" : shortClass(stack[0].getClassName()) + "." + stack[0].getMethodName();
        }
        return app == null || app.equals(api) ? api : api + " ← " + app;
    }

    private static String shortClass(String c) {
        int dot = c.lastIndexOf('.');
        return dot < 0 ? c : c.substring(dot + 1);
    }

    /** States and top frames as shares of worker-thread samples, most frequent first. */
    synchronized Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("samples", samples);
        m.put("threadSamples", threadSamples);
        m.put("periodMs", periodNanos / 1e6);
        m.put("blockedShare", threadSamples == 0 ? 0.0 : (double) blockedSamples / threadSamples);
        m.put("idleShare", threadSamples == 0 ? 0.0 : (double) idleSamples / threadSamples);
        Map<String, Double> st = new LinkedHashMap<>();
        states.entrySet().stream().sorted(Map.Entry.<Thread.State, Long>comparingByValue().reversed())
                .forEach(e -> st.put(e.getKey().name(), threadSamples == 0 ? 0.0 : (double) e.getValue() / threadSamples));
        m.put("states", st);
        List<Map<String, Object>> fr = new ArrayList<>();
        frames.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(6)
                .forEach(e -> fr.add(Map.of("frame", e.getKey(), "share", threadSamples == 0 ? 0.0 : (double) e.getValue() / threadSamples)));
        m.put("topFrames", fr);
        return m;
    }

    private long processCpuNanos() {
        return os instanceof com.sun.management.OperatingSystemMXBean sun ? sun.getProcessCpuTime() : -1;
    }

    /** Process CPU time ÷ (wall time × available processors) over the window, in percent; NaN if unknown. */
    synchronized double cpuUtilizationPercent() {
        if (cpuStart < 0 || cpuEnd < 0 || wallEnd <= wallStart) {
            return Double.NaN;
        }
        return 100.0 * (cpuEnd - cpuStart) / ((double) (wallEnd - wallStart) * Runtime.getRuntime().availableProcessors());
    }

    /** Share of worker samples inside a request but not running (sleeping, parked, blocked). */
    synchronized double blockedShare() {
        return threadSamples == 0 ? Double.NaN : (double) blockedSamples / threadSamples;
    }

    /** Share of worker samples idle in the pool, waiting for a request. */
    synchronized double idleShare() {
        return threadSamples == 0 ? Double.NaN : (double) idleSamples / threadSamples;
    }

    @Override
    public void close() throws InterruptedException {
        running = false;
        thread.join(1_000);
    }
}
