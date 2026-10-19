package dev.forge.lab.scenarios.jvm;

import dev.forge.lab.jvm.JfrCapture;
import dev.forge.lab.jvm.JfrMarker;
import dev.forge.lab.jvm.JfrSummariser;
import dev.forge.lab.jvm.JfrSummary;
import dev.forge.lab.scenario.Lab;
import dev.forge.lab.scenario.MetricSpec;
import dev.forge.lab.scenario.MetricSpec.Direction;
import dev.forge.lab.scenario.ParameterSpec;
import dev.forge.lab.scenario.RunContext;
import dev.forge.lab.scenario.RunSpec;
import dev.forge.lab.scenario.Scenario;
import dev.forge.lab.scenario.ScenarioDescriptor;
import dev.forge.lab.scenario.ScenarioResult;
import dev.forge.lab.scenario.ScenarioResult.InvariantSummary;
import dev.forge.lab.scenario.ScenarioResult.Trial;
import dev.forge.lab.scenario.VariantSpec;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Several threads update one shared accumulator, doing a fixed amount of work inside the critical
 * section, with a {@code synchronized} monitor, a shared {@link ReentrantLock}, or per-thread state
 * merged at the end. JFR records where threads waited and for how long; the JVM's own per-thread
 * contention counters give exact counts.
 *
 * <p>Invariant: the final count and checksum equal the values computed sequentially.
 */
public final class LockContentionScenario implements Scenario {

    public static final String ID = "jvm.lock-contention";
    private static final String SRC = LockContentionScenario.class.getName() + "#";

    /** The work done for every increment; a pure function of (thread, i), so the total is order-independent. */
    static long mix(int thread, int i, int rounds) {
        long x = ((long) thread << 32) ^ i;
        for (int r = 0; r < rounds; r++) {
            x = x * 6364136223846793005L + 1442695040888963407L;
        }
        return x >>> 17;
    }

    interface Accumulator {
        void add(int thread, int i, int rounds);

        long count();

        long checksum();

        /** Called by each worker when it is done (striped state is merged here). */
        default void finish(int thread) {
        }
    }

    // forge:snippet synchronized
    static final class SynchronizedAccumulator implements Accumulator {
        private long count;
        private long checksum;

        @Override
        public synchronized void add(int thread, int i, int rounds) {
            count++;
            checksum += mix(thread, i, rounds);       // the work happens while holding the monitor
        }

        @Override public synchronized long count() { return count; }
        @Override public synchronized long checksum() { return checksum; }
    }
    // forge:end

    // forge:snippet reentrant-lock
    static final class LockedAccumulator implements Accumulator {
        private final ReentrantLock lock = new ReentrantLock();   // non-fair; waiters park
        private long count;
        private long checksum;

        @Override
        public void add(int thread, int i, int rounds) {
            lock.lock();
            try {
                count++;
                checksum += mix(thread, i, rounds);
            } finally {
                lock.unlock();
            }
        }

        @Override public long count() { lock.lock(); try { return count; } finally { lock.unlock(); } }
        @Override public long checksum() { lock.lock(); try { return checksum; } finally { lock.unlock(); } }
    }
    // forge:end

    // forge:snippet striped
    static final class StripedAccumulator implements Accumulator {
        private final long[][] perThread;            // [thread][0 = count, 1 = checksum]; only its owner writes
        private long count;
        private long checksum;

        StripedAccumulator(int threads) {
            perThread = new long[threads][];
        }

        @Override
        public void add(int thread, int i, int rounds) {
            long[] mine = perThread[thread];
            if (mine == null) {
                mine = perThread[thread] = new long[2];   // allocated by the owning thread: no false sharing
            }
            mine[0]++;
            mine[1] += mix(thread, i, rounds);        // no shared state touched per increment
        }

        @Override
        public synchronized void finish(int thread) {  // one short lock per thread, at the end
            long[] mine = perThread[thread];
            if (mine != null) {
                count += mine[0];
                checksum += mine[1];
            }
        }

        @Override public synchronized long count() { return count; }
        @Override public synchronized long checksum() { return checksum; }
    }
    // forge:end

    static Accumulator accumulator(String variant, int threads) {
        return switch (variant) {
            case "synchronized" -> new SynchronizedAccumulator();
            case "reentrant-lock" -> new LockedAccumulator();
            case "striped" -> new StripedAccumulator(threads);
            default -> throw new IllegalArgumentException("unknown variant " + variant);
        };
    }

    private static final ScenarioDescriptor DESCRIPTOR = new ScenarioDescriptor(
            ID, 1, Lab.JVM,
            "Lock contention: who waited, where, and for how long",
            "Threads update one accumulator under a synchronized monitor, a ReentrantLock, or per-thread state. JFR shows the contended locks with stacks; the JVM's thread counters give exact block/wait counts.",
            "What does contention on one lock cost, and how does it show up for synchronized versus java.util.concurrent locks?",
            "Final count and checksum equal the sequentially computed values.",
            List.of(
                    ParameterSpec.integer("threads", "Threads", 4, 2, 16, "threads",
                            "Worker threads updating the shared accumulator."),
                    ParameterSpec.integer("incrementsPerThread", "Increments per thread", 200_000, 1_000, 1_000_000, "",
                            "Updates each worker performs."),
                    ParameterSpec.integer("workRounds", "Work inside the lock", 20, 0, 200, "rounds",
                            "Rounds of a multiply-add mix computed for every increment (inside the critical section for the locked variants).")),
            List.of(
                    new VariantSpec("synchronized", "synchronized", "One shared monitor; blocked threads are BLOCKED.", true, SRC + "synchronized"),
                    new VariantSpec("reentrant-lock", "ReentrantLock", "One shared j.u.c. lock; waiting threads park (WAITING).", true, SRC + "reentrant-lock"),
                    new VariantSpec("striped", "Per-thread state", "Each thread accumulates privately and merges once at the end.", true, SRC + "striped")),
            List.of(
                    new MetricSpec("elapsedMs", "Elapsed", "ms", Direction.LOWER_IS_BETTER,
                            "Wall-clock time from releasing the workers until the last one finished its increments."),
                    new MetricSpec("mopsPerSec", "Throughput", "M increments/s", Direction.HIGHER_IS_BETTER,
                            "Total increments divided by elapsed time."),
                    new MetricSpec("blockedCount", "Blocked (monitor)", "times", Direction.LOWER_IS_BETTER,
                            "Times workers entered BLOCKED waiting for a monitor (ThreadMXBean, exact)."),
                    new MetricSpec("blockedMs", "Time blocked", "ms", Direction.LOWER_IS_BETTER,
                            "Total time workers spent BLOCKED on monitors (ThreadMXBean contention monitoring)."),
                    new MetricSpec("waitedCount", "Waited (park)", "times", Direction.LOWER_IS_BETTER,
                            "Times workers entered WAITING/TIMED_WAITING — for a ReentrantLock this is parking (ThreadMXBean, exact)."),
                    new MetricSpec("waitedMs", "Time waiting", "ms", Direction.LOWER_IS_BETTER,
                            "Total time workers spent WAITING/TIMED_WAITING.")),
            "elapsedMs",
            new ScenarioDescriptor.TrialPolicy(5, 20, 1, 5),
            180,
            """
            When one thread holds a lock, others that need it must wait. HotSpot first spins briefly — the holder may be \
            about to release — and then blocks the waiter in the operating system, which costs a context switch on both \
            sides. A thread waiting for a synchronized monitor is in the BLOCKED state and JFR reports a \
            jdk.JavaMonitorEnter event; a thread waiting for a ReentrantLock parks (WAITING) and JFR reports \
            jdk.ThreadPark — the same problem in two different places of a profile. Per-thread state removes the shared \
            lock from the hot path: each worker accumulates privately and merges once. Doing more work inside the \
            critical section makes every hold longer and contention worse.""",
            List.of(
                    "JFR only records waits at or above its threshold (here 0.1 ms), so its event counts and totals are lower bounds; the ThreadMXBean counters count every block or wait, but time is measured in milliseconds by the JVM and short waits may round to zero.",
                    "Short waits are often absorbed by spinning: a contended acquisition that succeeds while spinning is neither BLOCKED nor parked and appears in no counter.",
                    "With more threads than cores, waiting includes time the holder was descheduled by the OS; results depend on the cores visible to the runner (recorded in the environment).",
                    "Thread contention monitoring (ThreadMXBean.setThreadContentionMonitoringEnabled) is switched on for the run; it adds a small cost to every block/wait.",
                    "Workers wait at the start and end of a trial by spinning, so that the counters are read while none is blocked or waiting; with more threads than cores, spinning workers take CPU from the ones still working.",
                    "The JFR recording covers the measured trials only."));

    @Override
    public ScenarioDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ScenarioResult run(RunSpec spec, RunContext ctx) throws Exception {
        VariantSpec variant = DESCRIPTOR.variant(spec.variant()).orElseThrow();
        int threads = spec.intParam("threads");
        int increments = spec.intParam("incrementsPerThread");
        int rounds = spec.intParam("workRounds");
        long expectedChecksum = 0;
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < increments; i++) {
                expectedChecksum += mix(t, i, rounds);
            }
        }
        long expectedCount = (long) threads * increments;
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        if (tmx.isThreadContentionMonitoringSupported()) {
            tmx.setThreadContentionMonitoringEnabled(true);
        }

        for (int w = 0; w < spec.warmupTrials(); w++) {
            ctx.checkCancelled();
            trial(variant.id(), threads, increments, rounds, tmx);
            ctx.progress(w + 1, spec.warmupTrials() + spec.trials(), "warm-up");
        }

        ScenarioResult.Builder result = ScenarioResult.builder();
        JfrCapture.Settings settings = JfrCapture.Settings.defaults()
                .withLockThresholds(Duration.ofNanos(100_000), Duration.ofNanos(100_000));
        JfrSummary jfr;
        try (JfrCapture capture = JfrCapture.start(ctx.workDir(), "contention", settings)) {
            for (int t = 0; t < spec.trials(); t++) {
                ctx.checkCancelled();
                JfrMarker.mark("trial-" + t + " start");
                Outcome o = trial(variant.id(), threads, increments, rounds, tmx);
                JfrMarker.mark("trial-" + t + " end");
                Map<String, Double> m = new LinkedHashMap<>();
                m.put("elapsedMs", o.nanos / 1e6);
                m.put("mopsPerSec", expectedCount / (o.nanos / 1e9) / 1e6);
                if (o.counters) {
                    m.put("blockedCount", (double) o.blockedCount);
                    m.put("blockedMs", (double) o.blockedMs);
                    m.put("waitedCount", (double) o.waitedCount);
                    m.put("waitedMs", (double) o.waitedMs);
                }
                boolean held = o.count == expectedCount && o.checksum == expectedChecksum;
                result.trial(new Trial(t, m, held, held ? null
                        : "count " + o.count + " (expected " + expectedCount + "), checksum " + (o.checksum == expectedChecksum ? "equal" : "different")));
                ctx.progress(spec.warmupTrials() + t + 1, spec.warmupTrials() + spec.trials(), "measured");
            }
            jfr = JvmLab.stopAndSummarise(capture, ctx, JfrSummariser.Options.forPackage(JvmLab.PACKAGE));
        }
        result.invariant(InvariantSummary.of(DESCRIPTOR.invariant(), result.trials()));

        var monitor = jfr.locks().monitorEnter();
        var park = jfr.locks().threadPark();
        result.observation(String.format("JFR (waits ≥ %.1f ms): %,d contended monitor enters totalling %.1f ms; %,d parks totalling %.1f ms.",
                monitor.thresholdMs(), monitor.events(), monitor.totalMs(), park.events(), park.totalMs()));
        double blocked = result.trials().stream().mapToDouble(t -> t.metrics().getOrDefault("blockedCount", 0.0)).sum();
        double waited = result.trials().stream().mapToDouble(t -> t.metrics().getOrDefault("waitedCount", 0.0)).sum();
        result.observation(String.format("ThreadMXBean over all measured trials: workers became BLOCKED %,.0f times and WAITING %,.0f times.", blocked, waited));
        result.observation("Final count and checksum correct in " + (spec.trials() - result.build().invariant().violations())
                + " of " + spec.trials() + " trials.");

        Map<String, Object> contention = new LinkedHashMap<>();
        contention.put("threads", threads);
        contention.put("incrementsPerThread", increments);
        contention.put("workRounds", rounds);
        contention.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        contention.put("contentionMonitoring", tmx.isThreadContentionMonitoringEnabled());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", "contention");
        details.put("jitMode", JvmLab.jitMode(variant));
        details.put("contention", contention);
        details.put("jfr", jfr);
        result.detail("jvm", details);
        return result.build();
    }

    private static final class Outcome {
        long nanos;
        long count;
        long checksum;
        boolean counters;
        long blockedCount;
        long blockedMs;
        long waitedCount;
        long waitedMs;
    }

    /**
     * One trial. The JVM's per-thread counters are read by this (driver) thread in one
     * {@code getThreadInfo(long[])} call before and after, while every worker is spinning — neither
     * blocked nor waiting. (Workers reading their own counters at the same moment contended on a
     * lock inside the management code and showed up as contention themselves.)
     */
    private static Outcome trial(String variant, int threads, int increments, int rounds, ThreadMXBean tmx)
            throws InterruptedException {
        Accumulator acc = accumulator(variant, threads);
        boolean counters = tmx.isThreadContentionMonitoringEnabled();
        AtomicBoolean go = new AtomicBoolean();
        AtomicBoolean release = new AtomicBoolean();
        AtomicInteger ready = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        long[] endNanos = new long[threads];
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            int id = t;
            workers[t] = Thread.ofPlatform().name("contender-" + t).start(() -> {
                ready.incrementAndGet();
                while (!go.get()) {                  // spin gates: a latch would park every worker
                    Thread.onSpinWait();
                }
                for (int i = 0; i < increments; i++) {
                    acc.add(id, i, rounds);
                }
                acc.finish(id);
                endNanos[id] = System.nanoTime();
                finished.incrementAndGet();          // publishes endNanos[id]
                while (!release.get()) {
                    Thread.onSpinWait();
                }
            });
        }
        long[] ids = java.util.Arrays.stream(workers).mapToLong(Thread::threadId).toArray();
        while (ready.get() < threads) {
            Thread.onSpinWait();
        }
        ThreadInfo[] before = counters ? tmx.getThreadInfo(ids) : null;
        long t0 = System.nanoTime();
        go.set(true);
        while (finished.get() < threads) {
            Thread.sleep(1);                         // the driver's own waiting is not measured
        }
        ThreadInfo[] after = counters ? tmx.getThreadInfo(ids) : null;
        release.set(true);
        for (Thread w : workers) {
            w.join();
        }
        Outcome o = new Outcome();
        o.nanos = java.util.Arrays.stream(endNanos).max().orElse(t0) - t0;
        o.count = acc.count();
        o.checksum = acc.checksum();
        o.counters = counters;
        if (counters) {
            for (int i = 0; i < threads; i++) {
                o.blockedCount += after[i].getBlockedCount() - before[i].getBlockedCount();
                o.blockedMs += after[i].getBlockedTime() - before[i].getBlockedTime();
                o.waitedCount += after[i].getWaitedCount() - before[i].getWaitedCount();
                o.waitedMs += after[i].getWaitedTime() - before[i].getWaitedTime();
            }
        }
        return o;
    }
}
