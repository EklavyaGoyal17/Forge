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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Livelock: two threads that keep reacting to each other and never get anywhere.
 *
 * <p>Two threads share a critical section guarded only by flags (no locks). The "polite" protocol
 * raises its flag, and if the other thread's flag is up, lowers its own and tries again. Neither
 * thread is ever blocked — both keep executing — yet in lockstep neither enters. Randomised back-off
 * makes that schedule improbable; Peterson's algorithm adds a tie-breaker ({@code turn}) that makes it
 * impossible.
 *
 * <p>Explorer mode builds the full state graph of the two looping threads and searches it for fair
 * cycles without progress ({@link LivenessExplorer}); stress mode runs the real protocol on two
 * cores and measures how long each thread waited to get in.
 */
public final class LivelockScenario implements Scenario {

    public static final String ID = "concurrency.livelock";
    private static final String SRC = LivelockScenario.class.getName() + "#";
    private static final String[] FLAG = {"flagA", "flagB"};
    private static final String[] NAME = {"A", "B"};

    /** Shared state. All fields are volatile (AtomicIntegerArray / volatile), so the JMM gives sequential consistency. */
    static final class Corridor {
        final AtomicIntegerArray flag = new AtomicIntegerArray(2);
        volatile int turn;
        final AtomicInteger inside = new AtomicInteger();   // threads currently in the critical section
        volatile boolean running = true;
        volatile int maxInside;
        final long[] entries = new long[2];
        final long[] backoffs = new long[2];
        final long[] maxWaitNanos = new long[2];

        /** Explorer snapshot: everything that determines the threads' futures. */
        String snapshot() {
            return "flagA=" + flag.get(0) + " flagB=" + flag.get(1) + " turn=" + NAME[turn] + " inside=" + inside.get();
        }
    }

    interface Protocol {
        void enter(Corridor c, int me, Probe p);

        default void exit(Corridor c, int me, Probe p) {
            p.write(FLAG[me], 0);
            c.flag.set(me, 0);
        }
    }

    // forge:snippet polite-flags
    static final class PoliteFlags implements Protocol {
        @Override
        public void enter(Corridor c, int me, Probe p) {
            int other = 1 - me;
            p.write(FLAG[me], 1);
            c.flag.set(me, 1);                  // "I would like to go in"
            while (true) {
                p.read(FLAG[other]);
                int theirs = c.flag.get(other);
                p.value(theirs);
                if (theirs == 0) {
                    return;                     // the other thread is not interested: go in
                }
                p.write(FLAG[me], 0);
                c.flag.set(me, 0);              // be polite: step aside...
                c.backoffs[me]++;
                p.write(FLAG[me], 1);
                c.flag.set(me, 1);              // ...and immediately try again. So does the other thread.
            }
        }
    }
    // forge:end

    // forge:snippet random-backoff
    static final class RandomBackoff implements Protocol {
        @Override
        public void enter(Corridor c, int me, Probe p) {
            int other = 1 - me;
            int limit = 16;
            c.flag.set(me, 1);
            while (c.flag.get(other) == 1) {
                c.flag.set(me, 0);              // step aside, then wait a random time before retrying:
                c.backoffs[me]++;               // two threads are unlikely to keep picking the same delay
                int spins = ThreadLocalRandom.current().nextInt(limit);
                for (int i = 0; i < spins; i++) {
                    Thread.onSpinWait();
                }
                limit = Math.min(limit * 2, 1 << 16);   // exponential: back off further after each collision
                c.flag.set(me, 1);
            }
        }
    }
    // forge:end

    // forge:snippet peterson
    static final class Peterson implements Protocol {
        @Override
        public void enter(Corridor c, int me, Probe p) {
            int other = 1 - me;
            p.write(FLAG[me], 1);
            c.flag.set(me, 1);                  // I want to go in...
            p.write("turn", other);
            c.turn = other;                     // ...but you go first if we both do (the tie-breaker)
            while (true) {
                p.read(FLAG[other]);
                int theirs = c.flag.get(other);
                p.value(theirs);
                if (theirs == 0) {
                    return;
                }
                p.read("turn");
                int t = c.turn;
                p.value(t);
                if (t != other) {
                    return;                     // the other thread gave way after me: my turn
                }
                Thread.onSpinWait();            // wait — but only one of us can be waiting here
            }
        }
    }
    // forge:end

    private static Protocol protocol(String variant) {
        return switch (variant) {
            case "polite-flags" -> new PoliteFlags();
            case "random-backoff" -> new RandomBackoff();
            case "peterson" -> new Peterson();
            default -> throw new IllegalArgumentException("unknown variant " + variant);
        };
    }

    /** One pass through the critical section; the probe calls make occupancy visible to the explorer. */
    private static void criticalSection(Corridor c, int me, Probe p) {
        p.atomic("inside", "enter critical section");
        int n = c.inside.incrementAndGet();
        if (n > c.maxInside) {
            c.maxInside = n;
        }
        p.progress("critical section");
        c.entries[me]++;
        p.atomic("inside", "leave critical section");
        c.inside.decrementAndGet();
    }

    private static final ScenarioDescriptor DESCRIPTOR = new ScenarioDescriptor(
            ID, 1, Lab.CONCURRENCY,
            "Livelock: polite threads that never get in",
            "Two threads share a critical section guarded only by flags. A polite protocol that steps aside on contention, randomised back-off, and Peterson's algorithm. Explorer mode searches the complete state graph for cycles in which nobody makes progress.",
            "Can two threads that are never blocked still fail to make progress — and what removes that possibility rather than just making it unlikely?",
            "Mutual exclusion (never both inside) and progress: no fair schedule loops forever without a thread entering (explorer); every thread enters and no wait exceeds the starvation threshold (stress).",
            List.of(
                    ParameterSpec.choice("mode", "Mode", "explore", List.of("explore", "stress"),
                            "explore: the complete state graph of both threads looping forever, searched for livelock and starvation cycles. stress: the real protocol on two threads for a fixed time."),
                    ParameterSpec.integer("durationMs", "Trial duration", 500, 50, 5_000, "ms", "How long both threads compete per trial.").onlyFor("stress"),
                    ParameterSpec.integer("starvationMs", "Starvation threshold", 100, 1, 5_000, "ms",
                            "A thread that waited longer than this to enter counts as starved in that trial.").onlyFor("stress"),
                    ParameterSpec.integer("maxStates", "State budget", 20_000, 100, 200_000, "states", "Upper bound on distinct states explored.").onlyFor("explore")),
            List.of(
                    new VariantSpec("polite-flags", "Polite flags (step aside, retry)",
                            "Raise flag; if the other flag is up, lower yours and raise it again. Never blocks; can livelock.", false, SRC + "polite-flags"),
                    new VariantSpec("random-backoff", "Randomised exponential back-off",
                            "Same protocol, but wait a random, growing time before retrying (as Ethernet does). Makes livelock improbable, not impossible — so it cannot be explored deterministically.", true, SRC + "random-backoff").onlyFor("stress"),
                    new VariantSpec("peterson", "Peterson's algorithm",
                            "Flags plus a turn variable: whoever set turn last waits. Mutual exclusion, no livelock, and bounded waiting.", true, SRC + "peterson")),
            List.of(
                    new MetricSpec("entriesPerSec", "Entries", "entries/s", Direction.HIGHER_IS_BETTER, "Critical-section entries per second, both threads together."),
                    new MetricSpec("maxWaitMs", "Longest wait", "ms", Direction.LOWER_IS_BETTER, "Longest time either thread spent trying to enter."),
                    new MetricSpec("minShare", "Smaller thread's share", "%", Direction.HIGHER_IS_BETTER, "Entries of the thread that entered less, as a share of all entries (50% = perfectly even)."),
                    new MetricSpec("backoffsPerEntry", "Back-offs per entry", "", Direction.LOWER_IS_BETTER, "Times a thread stepped aside, per entry."),
                    new MetricSpec("maxInside", "Max threads inside", "threads", Direction.LOWER_IS_BETTER, "Mutual exclusion holds while this is 1."),
                    new MetricSpec("states", "States", "", Direction.NEUTRAL, "Explorer: distinct reachable states."),
                    new MetricSpec("livelockCycles", "Livelock found", "", Direction.LOWER_IS_BETTER, "Explorer: 1 if a fair cycle without progress exists."),
                    new MetricSpec("starvedThreads", "Threads that can starve", "threads", Direction.LOWER_IS_BETTER, "Explorer: threads for which a fair cycle exists in which they never enter.")),
            "maxWaitMs",
            new ScenarioDescriptor.TrialPolicy(5, 50, 1, 5),
            120,
            """
            A **deadlock** is threads blocked forever. A **livelock** is threads that are busy forever: each reacts to the \
            other, and in lockstep the reactions cancel out. The polite protocol is the corridor dance — both step aside \
            at the same moment, both step back, again and again. Neither thread is ever blocked, so a thread dump looks \
            healthy and CPU usage is high; there is simply no progress.

            Whether it happens depends on the schedule, so stress testing on real cores rarely shows it: timing noise \
            breaks the symmetry within microseconds. That is why it is dangerous — and why explorer mode does not \
            sample schedules but builds every reachable state of the two looping threads and looks for a **cycle** \
            in which nobody enters. Only *fair* cycles count (both threads keep getting CPU time); a cycle where one thread \
            simply never runs is the scheduler's fault, not a livelock.

            **Randomised back-off** (Ethernet, lock retries, HTTP client retries with jitter) makes repeated collisions \
            exponentially unlikely, but a livelock schedule still exists. **Peterson's algorithm** removes it: when both \
            threads want in, `turn` names exactly one of them to wait, so the symmetric dance cannot repeat. The \
            explorer proves that for this model — and also that neither thread can be starved.""",
            List.of(
                    "Explorer results hold for this two-thread model at yield-point granularity under sequential consistency. The Java code is correct on the JVM because every shared field is volatile (AtomicIntegerArray), which makes these accesses sequentially consistent.",
                    "The explorer's fairness assumption is weak fairness; real schedulers are not guaranteed to be fair, but they do not adversarially pick the worst schedule either.",
                    "Randomised back-off cannot be explored deterministically (its behaviour depends on random numbers, not only on the schedule), so it runs in stress mode only.",
                    "Stress mode spins two threads; on a machine with fewer than two free cores, a thread preempted inside the critical section makes the other wait for the OS scheduler, which shows up as long waits unrelated to the protocol.",
                    "A stress run that shows no livelock is not evidence that none exists — the same holds for any timing-dependent bug."));

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
        Protocol protocol = protocol(spec.variant());
        int maxStates = spec.intParam("maxStates");
        ctx.info("building the state graph of two threads looping forever (budget " + maxStates + " states)");
        LivenessExplorer.Report r = LivenessExplorer.explore(
                Corridor::new,
                c -> List.of(task(c, 0, protocol), task(c, 1, protocol)),
                Corridor::snapshot,
                c -> c.inside.get() > 1 ? "both threads are inside the critical section" : null,
                maxStates,
                ctx::checkCancelled);
        return exploreResult(DESCRIPTOR, r, ctx);
    }

    private static NamedTask task(Corridor c, int me, Protocol protocol) {
        return new NamedTask(NAME[me], probe -> {
            while (c.running) {               // forever, in the explorer
                protocol.enter(c, me, probe);
                criticalSection(c, me, probe);
                protocol.exit(c, me, probe);
            }
        });
    }

    /** Shared by the liveness scenarios: verdicts, metrics and observations from a liveness report. */
    static ScenarioResult exploreResult(ScenarioDescriptor d, LivenessExplorer.Report r, RunContext ctx) {
        ScenarioResult.Builder result = ScenarioResult.builder();
        if (r.error() != null) {
            throw new IllegalStateException("liveness exploration failed: " + r.error());
        }
        boolean safe = r.safetyViolations() == 0 && r.deadlocks() == 0;
        boolean live = r.livelockFree() && r.starvationFree();
        boolean held = safe && live && r.complete();
        Boolean verdict = !safe || !live ? Boolean.FALSE : r.complete() ? Boolean.TRUE : null;
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("states", (double) r.states());
        m.put("livelockCycles", r.livelock() == null ? 0.0 : 1.0);
        m.put("starvedThreads", (double) r.starvation().size());
        result.trial(new Trial(0, m, verdict, null));
        result.invariant(new InvariantSummary(d.invariant(), 1, held || verdict == null ? 0 : 1, verdict));
        result.detail("liveness", LivenessDetails.of(r, Map.of()));

        result.observation(String.format("Explored %d distinct states and %d transitions in %d executions (%.0f ms)%s.",
                r.states(), r.edges(), r.executions(), r.elapsedNanos() / 1e6,
                r.complete() ? "; the state graph is complete" : "; the state budget ran out before the graph was complete"));
        result.observation(r.safetyViolations() == 0
                ? "Safety: no reachable state violates the invariant" + (r.complete() ? "." : " among those explored.")
                : r.safetyViolations() + " reachable states violate safety: " + r.firstSafetyViolation() + ".");
        if (r.deadlocks() > 0) {
            result.observation(r.deadlocks() + " reachable states are deadlocked (no thread can move).");
        }
        if (r.livelock() != null) {
            result.observation(String.format(
                    "Livelock: a fair cycle of %d steps exists in which every thread keeps running and none makes progress, reachable after %d steps. It can repeat forever.",
                    r.livelock().cycle().size(), r.livelock().prefix().size()));
        } else {
            result.observation(r.complete() ? "No livelock: every fair cycle in the complete graph contains progress."
                    : "No livelock cycle found in the explored part (incomplete graph: not a proof).");
        }
        if (!r.starvation().isEmpty()) {
            result.observation("Starvation possible for " + String.join(", ", r.starvation().keySet())
                    + ": a fair cycle exists in which that thread runs but never makes progress while others may.");
        } else if (r.complete()) {
            result.observation("No starvation: in every fair cycle, every thread makes progress.");
        }
        ctx.info(String.format("explored %d states: livelock %s, starvation %s", r.states(),
                r.livelock() == null ? "none" : "found", r.starvation().isEmpty() ? "none" : r.starvation().keySet()));
        return result.build();
    }

    // ---------------------------------------------------------------- stress mode

    private ScenarioResult stress(RunSpec spec, RunContext ctx) throws InterruptedException {
        long durationNanos = spec.longParam("durationMs") * 1_000_000L;
        long starvationNanos = spec.longParam("starvationMs") * 1_000_000L;
        ScenarioResult.Builder result = ScenarioResult.builder();
        int total = spec.warmupTrials() + spec.trials();
        for (int t = 0; t < total; t++) {
            ctx.checkCancelled();
            Corridor c = new Corridor();
            long elapsed = runStress(c, protocol(spec.variant()), durationNanos);
            ctx.progress(t + 1, total, t < spec.warmupTrials() ? "warm-up" : "measured");
            if (t < spec.warmupTrials()) {
                continue;
            }
            long entries = c.entries[0] + c.entries[1];
            long maxWait = Math.max(c.maxWaitNanos[0], c.maxWaitNanos[1]);
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("entriesPerSec", entries / (elapsed / 1e9));
            m.put("maxWaitMs", maxWait / 1e6);
            m.put("minShare", entries == 0 ? 0.0 : 100.0 * Math.min(c.entries[0], c.entries[1]) / entries);
            m.put("backoffsPerEntry", entries == 0 ? 0.0 : (double) (c.backoffs[0] + c.backoffs[1]) / entries);
            m.put("maxInside", (double) c.maxInside);
            List<String> problems = new ArrayList<>();
            if (c.maxInside > 1) {
                problems.add("both threads were inside the critical section at once");
            }
            for (int i = 0; i < 2; i++) {
                if (c.entries[i] == 0) {
                    problems.add("thread " + NAME[i] + " never entered");
                }
            }
            if (maxWait > starvationNanos) {
                problems.add(String.format("a thread waited %.1f ms to enter (threshold %d ms)", maxWait / 1e6, starvationNanos / 1_000_000));
            }
            result.trial(new Trial(t - spec.warmupTrials(), m, problems.isEmpty(), problems.isEmpty() ? null : String.join("; ", problems)));
        }
        InvariantSummary inv = InvariantSummary.of(DESCRIPTOR.invariant(), result.trials());
        result.invariant(inv);
        double[] wait = values(result.trials(), "maxWaitMs");
        double[] rate = values(result.trials(), "entriesPerSec");
        double[] share = values(result.trials(), "minShare");
        double[] backoffs = values(result.trials(), "backoffsPerEntry");
        result.observation("Invariant violated in " + inv.violations() + " of " + inv.checked() + " measured trials.");
        result.observation(String.format("Median across trials: %.0f entries/s, longest wait %.3f ms, smaller thread's share %.1f%%, %.2f back-offs per entry.",
                median(rate), median(wait), median(share), median(backoffs)));
        if (!"peterson".equals(spec.variant())) {
            result.observation("On real cores, timing noise breaks the lockstep that a livelock needs, so stress trials usually finish. Run explorer mode for the schedule that never ends.");
        }
        return result.build();
    }

    private static long runStress(Corridor c, Protocol protocol, long durationNanos) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Thread[] threads = new Thread[2];
        for (int i = 0; i < 2; i++) {
            int me = i;
            threads[i] = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                while (c.running) {
                    long t0 = System.nanoTime();
                    protocol.enter(c, me, Probe.NONE);
                    long waited = System.nanoTime() - t0;
                    if (waited > c.maxWaitNanos[me]) {
                        c.maxWaitNanos[me] = waited;
                    }
                    criticalSection(c, me, Probe.NONE);
                    protocol.exit(c, me, Probe.NONE);
                }
            }, "corridor-" + NAME[i]);
            threads[i].setDaemon(true);
            threads[i].start();
        }
        ready.await();
        long start = System.nanoTime();
        go.countDown();
        Thread.sleep(durationNanos / 1_000_000);
        c.running = false;
        for (Thread t : threads) {
            t.join(5_000);
            if (t.isAlive()) {
                // still trying to enter after the trial ended: release it by lowering the other flag
                c.flag.set(0, 0);
                c.flag.set(1, 0);
                t.join(1_000);
            }
        }
        return System.nanoTime() - start;
    }

    static double[] values(List<Trial> trials, String metric) {
        return trials.stream().map(t -> t.metrics().get(metric)).filter(v -> v != null && Double.isFinite(v))
                .mapToDouble(Double::doubleValue).sorted().toArray();
    }

    static double median(double[] sorted) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        double[] s = sorted.clone();
        Arrays.sort(s);
        return (s[(s.length - 1) / 2] + s[s.length / 2]) / 2;
    }
}
