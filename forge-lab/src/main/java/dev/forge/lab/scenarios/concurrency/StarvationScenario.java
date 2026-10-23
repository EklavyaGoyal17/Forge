package dev.forge.lab.scenarios.concurrency;

import dev.forge.lab.concurrency.ControlledExecution.NamedTask;
import dev.forge.lab.concurrency.LivenessExplorer;
import dev.forge.lab.concurrency.Probe;
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
import dev.forge.lab.stats.Summary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static dev.forge.lab.scenarios.concurrency.LivelockScenario.values;
import static dev.forge.lab.stats.Summary.median;

/**
 * Starvation: a reader–writer lock under continuous read traffic.
 *
 * <p>Readers may share the lock; a writer needs it alone. If readers are admitted whenever no writer
 * holds the lock, overlapping readers can keep it busy forever and a waiting writer never gets in —
 * the system as a whole makes progress (reads), but one thread does not. Preferring the writer just
 * moves the problem to the readers; a FIFO ticket lock bounds everybody's wait at the cost of
 * read concurrency. The JDK's {@link ReentrantReadWriteLock} is included for comparison in stress
 * mode.
 */
public final class StarvationScenario implements Scenario {

    public static final String ID = "concurrency.starvation";
    private static final String SRC = StarvationScenario.class.getName() + "#";

    /** Shared state; per-thread values that affect a thread's future ({@code ticket}) live here so the explorer sees them. */
    static final class Shared {
        final AtomicInteger rw = new AtomicInteger();          // readers inside (≥ 0), or −1 while a writer holds it
        volatile int writerWaiting;                            // writer-preference: a writer has announced itself
        final AtomicInteger next = new AtomicInteger();        // ticket lock: next ticket to hand out
        final AtomicInteger serving = new AtomicInteger();     // ticket lock: ticket now allowed in
        final int[] ticket;                                    // ticket lock: each thread's ticket (mod M), −1 = none
        final int modulus;
        final AtomicInteger readersInside = new AtomicInteger();
        final AtomicInteger writersInside = new AtomicInteger();
        volatile String violation;
        volatile boolean running = true;

        Shared(int threads) {
            this.ticket = new int[threads];
            Arrays.fill(ticket, -1);
            // Tickets are compared modulo M: M > threads gives every waiting thread a distinct ticket, and
            // a power of two keeps the arithmetic exact when the int counters overflow.
            this.modulus = Integer.highestOneBit(threads) << 1;
        }

        String snapshot() {
            return "rw=" + rw.get() + " writerWaiting=" + writerWaiting + " next=" + Math.floorMod(next.get(), modulus)
                    + " serving=" + Math.floorMod(serving.get(), modulus)
                    + " tickets=" + Arrays.toString(ticket) + " readers=" + readersInside.get() + " writers=" + writersInside.get();
        }

        String safety() {
            int r = readersInside.get();
            int w = writersInside.get();
            return w > 1 ? "two writers inside" : w == 1 && r > 0 ? "a writer and " + r + " reader(s) inside together" : null;
        }
    }

    interface RwLock {
        void lockRead(Shared s, int me, Probe p);

        void unlockRead(Shared s, int me, Probe p);

        void lockWrite(Shared s, int me, Probe p);

        void unlockWrite(Shared s, int me, Probe p);
    }

    // forge:snippet readers-preference
    static final class ReadersPreference implements RwLock {
        @Override
        public void lockRead(Shared s, int me, Probe p) {
            while (true) {
                p.read("rw");
                int v = s.rw.get();
                p.value(v);
                if (v >= 0) {                            // no writer inside: join the other readers
                    p.cas("rw", v, v + 1);
                    boolean ok = s.rw.compareAndSet(v, v + 1);
                    p.casResult(ok);
                    if (ok) {
                        return;
                    }
                }
                Thread.onSpinWait();
            }
        }

        @Override
        public void unlockRead(Shared s, int me, Probe p) {
            p.atomic("rw", "decrement");
            s.rw.decrementAndGet();
        }

        @Override
        public void lockWrite(Shared s, int me, Probe p) {
            while (true) {
                p.read("rw");
                int v = s.rw.get();
                p.value(v);
                if (v == 0) {                            // only when nobody at all is inside — with
                    p.cas("rw", 0, -1);                  // overlapping readers, that moment may never come
                    boolean ok = s.rw.compareAndSet(0, -1);
                    p.casResult(ok);
                    if (ok) {
                        return;
                    }
                }
                Thread.onSpinWait();
            }
        }

        @Override
        public void unlockWrite(Shared s, int me, Probe p) {
            p.write("rw", 0);
            s.rw.set(0);
        }
    }
    // forge:end

    // forge:snippet writer-preference
    static final class WriterPreference implements RwLock {
        private final ReadersPreference base = new ReadersPreference();

        @Override
        public void lockRead(Shared s, int me, Probe p) {
            while (true) {
                p.read("writerWaiting");
                int w = s.writerWaiting;
                p.value(w);
                if (w == 1) {                            // a writer is waiting: new readers hold back
                    Thread.onSpinWait();
                    continue;
                }
                p.read("rw");
                int v = s.rw.get();
                p.value(v);
                if (v >= 0) {
                    p.cas("rw", v, v + 1);
                    boolean ok = s.rw.compareAndSet(v, v + 1);
                    p.casResult(ok);
                    if (ok) {
                        return;
                    }
                }
                Thread.onSpinWait();
            }
        }

        @Override
        public void unlockRead(Shared s, int me, Probe p) {
            base.unlockRead(s, me, p);
        }

        @Override
        public void lockWrite(Shared s, int me, Probe p) {
            p.write("writerWaiting", 1);
            s.writerWaiting = 1;                         // announce: readers stop entering
            base.lockWrite(s, me, p);                    // wait for the readers already inside to leave
        }

        @Override
        public void unlockWrite(Shared s, int me, Probe p) {
            base.unlockWrite(s, me, p);
            p.write("writerWaiting", 0);
            s.writerWaiting = 0;                         // a writer that comes straight back re-announces itself
        }
    }
    // forge:end

    // forge:snippet ticket
    static class TicketLock implements RwLock {
        int takeTicket(Shared s, int me, Probe p) {
            p.atomic("next", "take a ticket (getAndIncrement)");
            return Math.floorMod(s.next.getAndIncrement(), s.modulus);   // one atomic step: wait-free
        }

        private void lock(Shared s, int me, Probe p) {
            s.ticket[me] = takeTicket(s, me, p);
            while (true) {                               // wait until that ticket is served: strict FIFO
                p.read("serving");
                int now = Math.floorMod(s.serving.get(), s.modulus);
                p.value(now);
                if (now == s.ticket[me]) {
                    return;
                }
                Thread.onSpinWait();
            }
        }

        private void unlock(Shared s, int me, Probe p) {
            s.ticket[me] = -1;
            p.atomic("serving", "serve the next ticket (increment)");
            s.serving.incrementAndGet();
        }

        // Readers and writers queue in one line, so nobody can be overtaken forever — but readers no
        // longer share the lock: fairness is paid for with read concurrency.
        @Override public void lockRead(Shared s, int me, Probe p) { lock(s, me, p); }
        @Override public void unlockRead(Shared s, int me, Probe p) { unlock(s, me, p); }
        @Override public void lockWrite(Shared s, int me, Probe p) { lock(s, me, p); }
        @Override public void unlockWrite(Shared s, int me, Probe p) { unlock(s, me, p); }
    }
    // forge:end

    // forge:snippet cas-ticket
    static final class CasTicketLock extends TicketLock {
        @Override
        int takeTicket(Shared s, int me, Probe p) {
            while (true) {                               // a CAS retry loop instead of one fetch-and-add:
                p.read("next");                          // lock-free (someone always succeeds), but a thread
                int t = s.next.get();                    // whose CAS keeps losing can retry forever
                p.value(t);
                p.cas("next", t, (t + 1) % s.modulus);   // the counter itself stays in [0, M)
                boolean ok = s.next.compareAndSet(t, (t + 1) % s.modulus);
                p.casResult(ok);
                if (ok) {
                    return t;
                }
            }
        }
    }
    // forge:end

    // forge:snippet jdk-nonfair
    static final class JdkLock implements RwLock {
        private final ReentrantReadWriteLock lock;

        JdkLock(boolean fair) {
            this.lock = new ReentrantReadWriteLock(fair);   // fair = FIFO hand-off to the longest waiter
        }

        @Override public void lockRead(Shared s, int me, Probe p) { lock.readLock().lock(); }
        @Override public void unlockRead(Shared s, int me, Probe p) { lock.readLock().unlock(); }
        @Override public void lockWrite(Shared s, int me, Probe p) { lock.writeLock().lock(); }
        @Override public void unlockWrite(Shared s, int me, Probe p) { lock.writeLock().unlock(); }
    }
    // forge:end

    private static RwLock lock(String variant) {
        return switch (variant) {
            case "readers-preference" -> new ReadersPreference();
            case "writer-preference" -> new WriterPreference();
            case "ticket" -> new TicketLock();
            case "cas-ticket" -> new CasTicketLock();
            case "jdk-nonfair" -> new JdkLock(false);
            case "jdk-fair" -> new JdkLock(true);
            default -> throw new IllegalArgumentException("unknown variant " + variant);
        };
    }

    private static void read(Shared s, Probe p, long holdNanos) {
        p.atomic("readers", "enter read section");
        s.readersInside.incrementAndGet();
        if (s.writersInside.get() > 0) {
            s.violation = "a reader entered while a writer was inside";
        }
        p.progress("read");
        busy(holdNanos);
        p.atomic("readers", "leave read section");
        s.readersInside.decrementAndGet();
    }

    private static void write(Shared s, Probe p, long holdNanos) {
        p.atomic("writers", "enter write section");
        int w = s.writersInside.incrementAndGet();
        if (w > 1 || s.readersInside.get() > 0) {
            s.violation = "a writer entered while others were inside";
        }
        p.progress("write");
        busy(holdNanos);
        p.atomic("writers", "leave write section");
        s.writersInside.decrementAndGet();
    }

    private static void busy(long nanos) {
        if (nanos <= 0) {
            return;
        }
        long until = System.nanoTime() + nanos;
        while (System.nanoTime() < until) {
            Thread.onSpinWait();
        }
    }

    private static final ScenarioDescriptor DESCRIPTOR = new ScenarioDescriptor(
            ID, 1, Lab.CONCURRENCY,
            "Starvation: a writer behind endless readers",
            "Readers share a lock, a writer needs it alone. Compare readers-preference and writer-preference locks, a FIFO ticket lock and the JDK's ReentrantReadWriteLock. Explorer mode searches every fair cycle for a thread that never gets in.",
            "When readers overlap continuously, does a waiting writer ever get the lock — and what does guaranteeing that it will cost the readers?",
            "Reader–writer exclusion holds, and no thread can be starved: no fair schedule keeps a thread waiting forever (explorer); every thread gets in and no writer waits longer than the starvation threshold (stress).",
            List.of(
                    ParameterSpec.choice("mode", "Mode", "explore", List.of("explore", "stress"),
                            "explore: the complete state graph of 2 readers and 1 writer looping forever. stress: real threads for a fixed time."),
                    ParameterSpec.integer("readers", "Readers", 3, 1, 8, "threads", "Reader threads; each re-acquires the read lock immediately after releasing it.").onlyFor("stress"),
                    ParameterSpec.integer("readHoldMicros", "Read hold time", 20, 0, 1_000, "µs", "Busy work while holding the read lock.").onlyFor("stress"),
                    ParameterSpec.integer("writeIntervalMs", "Write interval", 5, 1, 100, "ms", "The writer requests the lock this often.").onlyFor("stress"),
                    ParameterSpec.integer("writeHoldMicros", "Write hold time", 20, 0, 1_000, "µs", "Busy work while holding the write lock.").onlyFor("stress"),
                    ParameterSpec.integer("durationMs", "Trial duration", 1_000, 100, 5_000, "ms", "How long the threads run per trial.").onlyFor("stress"),
                    ParameterSpec.integer("starvationMs", "Starvation threshold", 250, 1, 5_000, "ms", "A wait longer than this counts as starvation in a stress trial.").onlyFor("stress"),
                    ParameterSpec.integer("maxStates", "State budget", 50_000, 100, 200_000, "states", "Upper bound on distinct states explored.").onlyFor("explore")),
            List.of(
                    new VariantSpec("readers-preference", "Readers-preference lock",
                            "Readers enter whenever no writer holds the lock; the writer waits until nobody is inside.", false, SRC + "readers-preference"),
                    new VariantSpec("writer-preference", "Writer-preference lock",
                            "A waiting writer stops new readers. Fixes the writer — and lets a busy writer starve the readers.", false, SRC + "writer-preference"),
                    new VariantSpec("ticket", "FIFO ticket lock",
                            "Everyone takes a ticket and is served in order: bounded waiting for all, but readers no longer share.", true, SRC + "ticket"),
                    new VariantSpec("cas-ticket", "Ticket lock, CAS-loop ticket",
                            "The same FIFO lock, but tickets are taken with a compare-and-set retry loop. Lock-free is not wait-free: a thread can lose the CAS forever.", false, SRC + "cas-ticket"),
                    new VariantSpec("jdk-nonfair", "ReentrantReadWriteLock (non-fair)",
                            "JDK default. No starvation guarantee per its documentation; a heuristic blocks new readers when a writer is first in the queue.", false, SRC + "jdk-nonfair").onlyFor("stress"),
                    new VariantSpec("jdk-fair", "ReentrantReadWriteLock (fair)",
                            "Fair mode: the lock goes to the longest-waiting thread; consecutive readers still share.", true, SRC + "jdk-nonfair").onlyFor("stress")),
            List.of(
                    new MetricSpec("writerWaitP99Ms", "Writer wait p99", "ms", Direction.LOWER_IS_BETTER, "99th percentile of the time from the writer's request to acquiring the lock."),
                    new MetricSpec("writerWaitMaxMs", "Writer wait max", "ms", Direction.LOWER_IS_BETTER,
                            "Longest writer wait. A request still waiting when the trial ended counts with its wait so far (a lower bound)."),
                    new MetricSpec("writesCompleted", "Writes", "writes", Direction.HIGHER_IS_BETTER, "Writes completed during the trial."),
                    new MetricSpec("writesRequested", "Writes requested", "writes", Direction.NEUTRAL, "Write requests made during the trial."),
                    new MetricSpec("readsPerSec", "Reads", "reads/s", Direction.HIGHER_IS_BETTER, "Read sections completed per second, all readers together."),
                    new MetricSpec("readerWaitMaxMs", "Reader wait max", "ms", Direction.LOWER_IS_BETTER, "Longest time a reader waited for the read lock."),
                    new MetricSpec("maxConcurrentReaders", "Peak concurrent readers", "readers", Direction.NEUTRAL, "Most readers observed inside together: 1 means reads were serialised."),
                    new MetricSpec("states", "States", "", Direction.NEUTRAL, "Explorer: distinct reachable states."),
                    new MetricSpec("livelockCycles", "Livelock found", "", Direction.LOWER_IS_BETTER, "Explorer: 1 if a fair cycle without any progress exists."),
                    new MetricSpec("starvedThreads", "Threads that can starve", "threads", Direction.LOWER_IS_BETTER, "Explorer: threads for which a fair cycle exists in which they never get in.")),
            "writerWaitP99Ms",
            new ScenarioDescriptor.TrialPolicy(5, 30, 1, 5),
            180,
            """
            **Starvation** is a liveness failure of one thread rather than the whole system: work gets done, just never \
            this thread's. A readers-preference lock admits a reader whenever no writer is inside. With readers \
            arriving faster than they leave, the read count never drops to zero, and the writer — which needs it to be \
            zero — waits indefinitely while throughput looks excellent.

            Turning the preference around (block new readers once a writer is waiting) rescues the writer but lets a \
            writer that comes straight back starve the readers. A **FIFO ticket lock** serves requests strictly in \
            order, so every wait is bounded by the work queued ahead of it; here readers and writers share one line, \
            so reads are serialised — the price of the guarantee is visible as read throughput and peak concurrency. \
            The JDK's `ReentrantReadWriteLock` in fair mode hands the lock to the longest waiter while still letting a \
            run of consecutive readers share it.

            Explorer mode builds every reachable state of two looping readers and one looping writer and searches for \
            a **fair cycle** in which one thread runs but never gets in. Such a cycle is a schedule that can repeat \
            forever — a proof that starvation is possible, independent of how likely the timing is on a real machine.""",
            List.of(
                    "Explorer results hold for the 2-reader, 1-writer model at yield-point granularity under sequential consistency; the JDK lock variants are not instrumented and run in stress mode only.",
                    "The ticket counter wraps modulo (threads + 1), which is safe because a thread holds at most one ticket. Real ticket locks wrap at 2³² for the same reason.",
                    "Stress readers spin while holding the lock and re-acquire immediately: continuous overlapping reads by design. With fewer cores than readers + writer, OS scheduling adds waits unrelated to the lock.",
                    "Whether a writer starves under stress depends on timing; a trial that shows no starvation is not evidence that starvation is impossible.",
                    "A write request still pending when the trial ends is counted with its wait so far, so the reported maximum is a lower bound for starved writers."));

    @Override
    public ScenarioDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ScenarioResult run(RunSpec spec, RunContext ctx) throws Exception {
        if (DESCRIPTOR.variant(spec.variant()).map(v -> !v.supportsMode(spec.stringParam("mode"))).orElse(true)) {
            throw new IllegalArgumentException("variant " + spec.variant() + " does not support mode " + spec.stringParam("mode"));
        }
        return spec.stringParam("mode").equals("explore") ? explore(spec, ctx) : stress(spec, ctx);
    }

    // ---------------------------------------------------------------- explore mode

    private ScenarioResult explore(RunSpec spec, RunContext ctx) {
        RwLock lock = lock(spec.variant());
        int maxStates = spec.intParam("maxStates");
        ctx.info("building the state graph of 2 readers and 1 writer looping forever (budget " + maxStates + " states)");
        LivenessExplorer.Report r = LivenessExplorer.explore(
                () -> new Shared(3),
                s -> List.of(reader(s, 0, "reader-1", lock), reader(s, 1, "reader-2", lock), writer(s, 2, lock)),
                Shared::snapshot,
                Shared::safety,
                maxStates,
                ctx::checkCancelled);
        return LivelockScenario.exploreResult(DESCRIPTOR, r, ctx);
    }

    private static NamedTask reader(Shared s, int me, String name, RwLock lock) {
        return new NamedTask(name, p -> {
            while (s.running) {
                lock.lockRead(s, me, p);
                read(s, p, 0);
                lock.unlockRead(s, me, p);
            }
        });
    }

    private static NamedTask writer(Shared s, int me, RwLock lock) {
        return new NamedTask("writer", p -> {
            while (s.running) {
                lock.lockWrite(s, me, p);
                write(s, p, 0);
                lock.unlockWrite(s, me, p);
            }
        });
    }

    // ---------------------------------------------------------------- stress mode

    private ScenarioResult stress(RunSpec spec, RunContext ctx) throws InterruptedException {
        int readers = spec.intParam("readers");
        long readHold = spec.longParam("readHoldMicros") * 1_000L;
        long writeHold = spec.longParam("writeHoldMicros") * 1_000L;
        long intervalNanos = spec.longParam("writeIntervalMs") * 1_000_000L;
        long durationNanos = spec.longParam("durationMs") * 1_000_000L;
        long starvationNanos = spec.longParam("starvationMs") * 1_000_000L;
        ScenarioResult.Builder result = ScenarioResult.builder();
        int total = spec.warmupTrials() + spec.trials();
        for (int t = 0; t < total; t++) {
            ctx.checkCancelled();
            StressOutcome o = runStress(lock(spec.variant()), readers, readHold, writeHold, intervalNanos, durationNanos);
            ctx.progress(t + 1, total, t < spec.warmupTrials() ? "warm-up" : "measured");
            if (t < spec.warmupTrials()) {
                continue;
            }
            long[] waits = Arrays.copyOf(o.writerWaits, o.writes);
            Arrays.sort(waits);
            long maxWait = Math.max(waits.length == 0 ? 0 : waits[waits.length - 1], o.pendingWriteNanos);
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("writerWaitP99Ms", waits.length == 0 ? o.pendingWriteNanos / 1e6 : waits[Math.max(0, (int) Math.ceil(0.99 * waits.length) - 1)] / 1e6);
            m.put("writerWaitMaxMs", maxWait / 1e6);
            m.put("writesCompleted", (double) o.writes);
            m.put("writesRequested", (double) o.requested);
            m.put("readsPerSec", o.reads / (o.elapsedNanos / 1e9));
            m.put("readerWaitMaxMs", o.maxReaderWaitNanos / 1e6);
            m.put("maxConcurrentReaders", (double) o.maxConcurrentReaders);
            List<String> problems = new ArrayList<>();
            if (o.violation != null) {
                problems.add(o.violation);
            }
            if (o.writes == 0) {
                problems.add("the writer never got the lock (" + o.requested + " request, waited " + String.format("%.0f", o.pendingWriteNanos / 1e6) + " ms)");
            }
            if (o.readersWithoutProgress > 0) {
                problems.add(o.readersWithoutProgress + " reader(s) never got the lock");
            }
            if (maxWait > starvationNanos) {
                problems.add(String.format("writer waited %.1f ms (threshold %d ms)", maxWait / 1e6, starvationNanos / 1_000_000));
            }
            result.trial(new Trial(t - spec.warmupTrials(), m, problems.isEmpty(), problems.isEmpty() ? null : String.join("; ", problems)));
        }
        InvariantSummary inv = InvariantSummary.of(DESCRIPTOR.invariant(), result.trials());
        result.invariant(inv);
        result.observation("Invariant violated in " + inv.violations() + " of " + inv.checked() + " measured trials.");
        result.observation(String.format("Median across trials: writer wait p99 %.3f ms (max %.3f ms), %.0f of %.0f requested writes completed, %.0f reads/s, peak %.0f concurrent readers.",
                median(values(result.trials(), "writerWaitP99Ms")), median(values(result.trials(), "writerWaitMaxMs")),
                median(values(result.trials(), "writesCompleted")), median(values(result.trials(), "writesRequested")),
                median(values(result.trials(), "readsPerSec")), median(values(result.trials(), "maxConcurrentReaders"))));
        if ("ticket".equals(spec.variant())) {
            result.observation("The ticket lock serialises readers (peak concurrent readers 1): bounded waiting is bought with read concurrency.");
        }
        return result.build();
    }

    private static final class StressOutcome {
        long reads;
        int writes;
        int requested;
        long[] writerWaits = new long[16];
        long pendingWriteNanos;
        long maxReaderWaitNanos;
        int maxConcurrentReaders;
        int readersWithoutProgress;
        long elapsedNanos;
        String violation;
    }

    private static StressOutcome runStress(RwLock lock, int readers, long readHold, long writeHold, long intervalNanos,
                                           long durationNanos) throws InterruptedException {
        Shared s = new Shared(readers + 1);
        StressOutcome o = new StressOutcome();
        long[] readsBy = new long[readers];
        long[] readerMaxWait = new long[readers];
        AtomicInteger peakReaders = new AtomicInteger();
        long[] requestedAt = {0};
        boolean[] writerWaiting = {false};
        CountDownLatch ready = new CountDownLatch(readers + 1);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < readers; i++) {
            int me = i;
            threads.add(new Thread(() -> {
                ready.countDown();
                await(go);
                while (s.running) {
                    long t0 = System.nanoTime();
                    lock.lockRead(s, me, Probe.NONE);
                    long w = System.nanoTime() - t0;
                    if (w > readerMaxWait[me]) {
                        readerMaxWait[me] = w;
                    }
                    peakReaders.accumulateAndGet(s.readersInside.get() + 1, Math::max);
                    read(s, Probe.NONE, readHold);
                    readsBy[me]++;
                    lock.unlockRead(s, me, Probe.NONE);
                }
            }, "reader-" + (i + 1)));
        }
        Object writerLock = new Object();
        threads.add(new Thread(() -> {
            ready.countDown();
            await(go);
            long next = System.nanoTime() + intervalNanos;
            while (s.running) {
                long wait;
                while ((wait = next - System.nanoTime()) > 0 && s.running) {
                    LockSupport.parkNanos(Math.min(wait, 1_000_000));
                }
                if (!s.running) {
                    break;
                }
                long t0 = System.nanoTime();
                synchronized (writerLock) {
                    requestedAt[0] = t0;
                    writerWaiting[0] = true;
                    o.requested++;
                }
                lock.lockWrite(s, readers, Probe.NONE);
                long waited = System.nanoTime() - t0;
                synchronized (writerLock) {
                    writerWaiting[0] = false;
                    if (s.running) {   // a request granted only because the trial stopped the readers is not a real grant
                        if (o.writes == o.writerWaits.length) {
                            o.writerWaits = Arrays.copyOf(o.writerWaits, o.writes * 2);
                        }
                        o.writerWaits[o.writes++] = waited;
                    } else {
                        o.pendingWriteNanos = Math.max(o.pendingWriteNanos, waited);
                    }
                }
                write(s, Probe.NONE, writeHold);
                lock.unlockWrite(s, readers, Probe.NONE);
                next = Math.max(next + intervalNanos, System.nanoTime());
            }
        }, "writer"));
        threads.forEach(t -> {
            t.setDaemon(true);
            t.start();
        });
        ready.await();
        long start = System.nanoTime();
        go.countDown();
        Thread.sleep(durationNanos / 1_000_000);
        long stop = System.nanoTime();
        synchronized (writerLock) {
            if (writerWaiting[0]) {
                o.pendingWriteNanos = stop - requestedAt[0];   // still waiting: censored at the trial end
            }
        }
        s.running = false;
        for (Thread t : threads) {
            t.join(5_000);
        }
        synchronized (writerLock) {
            o.elapsedNanos = stop - start;
            for (int i = 0; i < readers; i++) {
                o.reads += readsBy[i];
                o.maxReaderWaitNanos = Math.max(o.maxReaderWaitNanos, readerMaxWait[i]);
                if (readsBy[i] == 0) {
                    o.readersWithoutProgress++;
                }
            }
            o.maxConcurrentReaders = peakReaders.get();
            o.violation = s.violation;
        }
        return o;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
