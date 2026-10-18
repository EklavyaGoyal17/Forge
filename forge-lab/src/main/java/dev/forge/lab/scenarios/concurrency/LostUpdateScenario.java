package dev.forge.lab.scenarios.concurrency;

import dev.forge.lab.concurrency.ControlledExecution.NamedTask;
import dev.forge.lab.concurrency.InterleavingExplorer;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The Lost Update Problem: several threads deposit into one shared balance.
 *
 * <p>Invariant: after {@code T} threads each perform {@code K} deposits of 1, the balance is
 * exactly {@code T·K}.
 */
public final class LostUpdateScenario implements Scenario {

    public static final String ID = "concurrency.lost-update";
    private static final String SRC = LostUpdateScenario.class.getName() + "#";

    /** A shared balance. Every implementation receives the probe so one codebase serves both modes. */
    interface Balance {
        void deposit(Probe probe);

        long balance();
    }

    // forge:snippet unsynchronized
    static final class UnsynchronizedBalance implements Balance {
        private long balance;

        @Override
        public void deposit(Probe probe) {
            probe.read("balance");
            long current = balance;            // 1. read
            probe.value(current);
            probe.write("balance", current + 1);
            balance = current + 1;             // 2. write — another thread may have written in between
        }

        @Override
        public long balance() {
            return balance;
        }
    }
    // forge:end

    // forge:snippet volatile
    static final class VolatileBalance implements Balance {
        private volatile long balance;         // fixes visibility, NOT atomicity

        @Override
        public void deposit(Probe probe) {
            probe.read("balance");
            long current = balance;            // volatile read
            probe.value(current);
            probe.write("balance", current + 1);
            balance = current + 1;             // volatile write — the read-modify-write is still two steps
        }

        @Override
        public long balance() {
            return balance;
        }
    }
    // forge:end

    // forge:snippet synchronized
    static final class SynchronizedBalance implements Balance {
        private long balance;

        @Override
        public void deposit(Probe probe) {
            probe.acquire("monitor");
            synchronized (this) {              // mutual exclusion + happens-before on exit/entry
                probe.read("balance");
                long current = balance;
                probe.value(current);
                probe.write("balance", current + 1);
                balance = current + 1;
                probe.release("monitor");
            }
        }

        @Override
        public synchronized long balance() {
            return balance;
        }
    }
    // forge:end

    // forge:snippet reentrant-lock
    static final class LockedBalance implements Balance {
        private final ReentrantLock lock = new ReentrantLock();
        private long balance;

        @Override
        public void deposit(Probe probe) {
            probe.acquire("lock");
            lock.lock();
            try {
                probe.read("balance");
                long current = balance;
                probe.value(current);
                probe.write("balance", current + 1);
                balance = current + 1;
            } finally {
                probe.release("lock");
                lock.unlock();
            }
        }

        @Override
        public long balance() {
            lock.lock();
            try {
                return balance;
            } finally {
                lock.unlock();
            }
        }
    }
    // forge:end

    // forge:snippet atomic-cas
    static final class CasBalance implements Balance {
        private final AtomicLong balance = new AtomicLong();

        @Override
        public void deposit(Probe probe) {
            while (true) {                     // optimistic: retry if another thread got there first
                probe.read("balance");
                long current = balance.get();
                probe.value(current);
                probe.cas("balance", current, current + 1);
                boolean updated = balance.compareAndSet(current, current + 1);
                probe.casResult(updated);
                if (updated) {
                    return;
                }
            }
        }

        @Override
        public long balance() {
            return balance.get();
        }
    }
    // forge:end

    // forge:snippet long-adder
    static final class AdderBalance implements Balance {
        private final LongAdder balance = new LongAdder();   // striped cells: less contention, slower reads

        @Override
        public void deposit(Probe probe) {
            probe.atomic("balance", "increment");
            balance.increment();
        }

        @Override
        public long balance() {
            return balance.sum();              // not an atomic snapshot while writers are active
        }
    }
    // forge:end

    static Supplier<Balance> factory(String variant) {
        return switch (variant) {
            case "unsynchronized" -> UnsynchronizedBalance::new;
            case "volatile" -> VolatileBalance::new;
            case "synchronized" -> SynchronizedBalance::new;
            case "reentrant-lock" -> LockedBalance::new;
            case "atomic-cas" -> CasBalance::new;
            case "long-adder" -> AdderBalance::new;
            default -> throw new IllegalArgumentException("unknown variant " + variant);
        };
    }

    private static final ScenarioDescriptor DESCRIPTOR = new ScenarioDescriptor(
            ID, 1, Lab.CONCURRENCY,
            "The Lost Update Problem",
            "Several threads deposit into one shared balance. Compare broken and correct implementations under real contention (stress) and under exhaustive controlled scheduling (explorer).",
            "Which implementations of a shared counter preserve every update under concurrency, and what does correctness cost in throughput?",
            "After T threads each deposit K times, balance == T × K.",
            List.of(
                    ParameterSpec.choice("mode", "Mode", "stress", List.of("stress", "explore"),
                            "stress: real threads at full speed, statistical. explore: one thread at a time between yield points, every interleaving enumerated."),
                    ParameterSpec.integer("threads", "Threads", 4, 2, 64, "threads",
                            "Concurrent depositing threads.").onlyFor("stress"),
                    ParameterSpec.integer("depositsPerThread", "Deposits per thread", 100_000, 1, 5_000_000, "ops",
                            "Deposits each thread performs per trial.").onlyFor("stress"),
                    ParameterSpec.integer("exploreThreads", "Threads", 2, 2, 3, "threads",
                            "Threads in the explored program. The schedule space grows factorially.").onlyFor("explore"),
                    ParameterSpec.integer("exploreDeposits", "Deposits per thread", 2, 1, 3, "ops",
                            "Deposits per thread in the explored program.").onlyFor("explore"),
                    ParameterSpec.integer("maxSchedules", "Schedule budget", 5_000, 1, 50_000, "schedules",
                            "Upper bound on executions. If reached, the result is reported as non-exhaustive.").onlyFor("explore")),
            List.of(
                    new VariantSpec("unsynchronized", "Plain field", "balance = balance + 1 on a plain long. Read and write are separate steps.", false, SRC + "unsynchronized"),
                    new VariantSpec("volatile", "volatile field", "Makes every write visible to other threads promptly, but read-modify-write is still not atomic.", false, SRC + "volatile"),
                    new VariantSpec("synchronized", "synchronized", "Intrinsic monitor: one thread in the critical section at a time.", true, SRC + "synchronized"),
                    new VariantSpec("reentrant-lock", "ReentrantLock", "Explicit lock with the same mutual-exclusion guarantee; supports tryLock and fairness options.", true, SRC + "reentrant-lock"),
                    new VariantSpec("atomic-cas", "AtomicLong CAS loop", "Lock-free optimistic update: read, compare-and-set, retry on conflict.", true, SRC + "atomic-cas"),
                    new VariantSpec("long-adder", "LongAdder", "Striped counter: contended updates go to different cells; sum() adds them up.", true, SRC + "long-adder")),
            List.of(
                    new MetricSpec("throughputMopsPerSec", "Throughput", "M deposits/s", Direction.HIGHER_IS_BETTER, "Total deposits divided by wall-clock time from start gate to last thread finishing."),
                    new MetricSpec("durationMs", "Duration", "ms", Direction.LOWER_IS_BETTER, "Wall-clock time of the trial."),
                    new MetricSpec("lostUpdates", "Lost updates", "deposits", Direction.LOWER_IS_BETTER, "expected − observed balance."),
                    new MetricSpec("lostPercent", "Lost updates", "%", Direction.LOWER_IS_BETTER, "Share of deposits that disappeared."),
                    new MetricSpec("expected", "Expected balance", "", Direction.NEUTRAL, "T × K."),
                    new MetricSpec("observed", "Observed balance", "", Direction.NEUTRAL, "Balance after all threads joined."),
                    new MetricSpec("schedulesExplored", "Schedules explored", "", Direction.NEUTRAL, "Explorer mode: executions performed."),
                    new MetricSpec("violatingSchedules", "Violating schedules", "", Direction.LOWER_IS_BETTER, "Explorer mode: schedules whose final balance broke the invariant.")),
            "throughputMopsPerSec",
            new ScenarioDescriptor.TrialPolicy(20, 200, 3, 20),
            120,
            """
            A deposit is a read-modify-write: read the balance, add one, write it back. If two threads both read 41 \
            before either writes, both write 42 and one deposit is lost. `volatile` does not help: it guarantees that a \
            write becomes visible, not that the read and the write happen without another thread's write in between. \
            Mutual exclusion (`synchronized`, `ReentrantLock`) serialises the critical section; `compareAndSet` detects \
            interference and retries; `LongAdder` avoids most interference by spreading updates over cells.

            Stress mode shows how often the race happens on this machine. Explorer mode shows that it *can* happen, \
            by enumerating every ordering of the instrumented steps and producing a concrete losing schedule.""",
            List.of(
                    "Stress mode is statistical: zero violations in N trials does not prove a variant correct.",
                    "On HotSpot, C2 may hoist a plain field's load and store out of the deposit loop, so the unsynchronized variant can lose far more updates than intuition suggests — the race is then per-loop, not per-deposit.",
                    "Explorer mode assumes sequential consistency, so the plain and volatile variants behave identically there; their real difference (visibility) is not an atomicity property.",
                    "Throughput of correct variants depends heavily on core count and contention; single-digit thread counts on a laptop do not predict behaviour on a 64-core server.",
                    "LongAdder.sum() is not an atomic snapshot; it is exact here only because it is read after all writers have joined."));

    @Override
    public ScenarioDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ScenarioResult run(RunSpec spec, RunContext ctx) throws Exception {
        return spec.stringParam("mode").equals("explore") ? explore(spec, ctx) : stress(spec, ctx);
    }

    // ---------------------------------------------------------------- stress mode

    private ScenarioResult stress(RunSpec spec, RunContext ctx) throws InterruptedException {
        int threads = spec.intParam("threads");
        int deposits = spec.intParam("depositsPerThread");
        Supplier<Balance> factory = factory(spec.variant());
        ScenarioResult.Builder result = ScenarioResult.builder();
        int total = spec.warmupTrials() + spec.trials();
        ctx.info("stress: " + threads + " threads × " + deposits + " deposits, " + spec.warmupTrials()
                + " warm-up + " + spec.trials() + " measured trials");

        for (int t = 0; t < total; t++) {
            ctx.checkCancelled();
            boolean warmup = t < spec.warmupTrials();
            Balance balance = factory.get();
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch startGate = new CountDownLatch(1);
            Thread[] workers = new Thread[threads];
            for (int i = 0; i < threads; i++) {
                workers[i] = new Thread(() -> {
                    ready.countDown();
                    try {
                        startGate.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    for (int k = 0; k < deposits; k++) {
                        balance.deposit(Probe.NONE);
                    }
                }, "depositor-" + i);
                workers[i].setDaemon(true);
                workers[i].start();
            }
            ready.await();
            long begin = System.nanoTime();
            startGate.countDown();
            for (Thread w : workers) {
                long remaining = ctx.deadlineNanos() - System.nanoTime();
                if (remaining <= 0 || !joinNanos(w, remaining)) {
                    throw new RunContext.CancelledException("trial " + t + " did not finish before the run deadline");
                }
            }
            long elapsed = System.nanoTime() - begin;

            long expected = (long) threads * deposits;
            long observed = balance.balance();
            long lost = expected - observed;
            if (warmup) {
                ctx.progress(t + 1, total, "warm-up");
                continue;
            }
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("expected", (double) expected);
            m.put("observed", (double) observed);
            m.put("lostUpdates", (double) lost);
            m.put("lostPercent", 100.0 * lost / expected);
            m.put("durationMs", elapsed / 1e6);
            m.put("throughputMopsPerSec", expected / (elapsed / 1e9) / 1e6);
            result.trial(new Trial(t - spec.warmupTrials(), m, observed == expected,
                    observed == expected ? null : lost + " deposits lost"));
            ctx.progress(t + 1, total, "measured");
        }

        List<Trial> trials = result.trials();
        InvariantSummary inv = InvariantSummary.of(DESCRIPTOR.invariant(), trials);
        result.invariant(inv);
        result.observation("Invariant violated in " + inv.violations() + " of " + inv.checked() + " measured trials.");
        if (inv.violations() > 0) {
            double maxLost = trials.stream().mapToDouble(x -> x.metrics().get("lostPercent")).max().orElse(0);
            double minLost = trials.stream().mapToDouble(x -> x.metrics().get("lostPercent")).min().orElse(0);
            result.observation(String.format("Lost updates ranged from %.3f%% to %.3f%% of deposits per trial.", minLost, maxLost));
        } else if (Boolean.FALSE.equals(DESCRIPTOR.variant(spec.variant()).map(VariantSpec::expectedCorrect).orElse(null))) {
            result.observation("No lost updates were observed, although this variant is racy: the race did not manifest in these trials — that does not mean it cannot.");
        } else {
            result.observation("No lost updates were observed in " + inv.checked() + " trials. This is consistent with a correct implementation; it is not a proof.");
        }
        // Plausibility check: a chain of dependent load-add-store updates to one field runs at roughly one
        // per nanosecond per thread on current CPUs (store-to-load forwarding latency). A faster measured
        // rate means the executed loop was not one update per deposit, so its throughput is not comparable.
        double[] perThreadPerNs = trials.stream()
                .mapToDouble(x -> x.metrics().get("throughputMopsPerSec") / threads / 1_000.0)
                .sorted().toArray();
        double medianRate = perThreadPerNs[perThreadPerNs.length / 2];
        if (medianRate > 1.0) {
            result.observation(String.format(
                    "Plausibility check: the median rate is %.1f deposits per nanosecond per thread — faster than a chain of dependent load-add-store updates can run (about one per nanosecond). The measured loop almost certainly did not perform one memory update per deposit (the JIT may combine a plain field's increments), so this variant's throughput is not comparable with the others.",
                    medianRate));
        }
        return result.build();
    }

    private static boolean joinNanos(Thread t, long nanos) throws InterruptedException {
        t.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos)));
        return !t.isAlive();
    }

    // ---------------------------------------------------------------- explorer mode

    private ScenarioResult explore(RunSpec spec, RunContext ctx) {
        int threads = spec.intParam("exploreThreads");
        int deposits = spec.intParam("exploreDeposits");
        int budget = spec.intParam("maxSchedules");
        Supplier<Balance> factory = factory(spec.variant());
        long expected = (long) threads * deposits;
        ctx.info("explore: " + threads + " threads × " + deposits + " deposits, budget " + budget + " schedules");

        InterleavingExplorer.Report report = InterleavingExplorer.explore(
                factory,
                balance -> {
                    List<NamedTask> tasks = new ArrayList<>();
                    for (int i = 0; i < threads; i++) {
                        tasks.add(new NamedTask("T" + (i + 1), probe -> {
                            for (int k = 0; k < deposits; k++) {
                                balance.deposit(probe);
                            }
                        }));
                    }
                    return tasks;
                },
                balance -> new InterleavingExplorer.Check(balance.balance() == expected, "balance = " + balance.balance()),
                budget, 10_000, ctx::checkCancelled);

        ScenarioResult.Builder result = ScenarioResult.builder();
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("schedulesExplored", (double) report.schedulesExplored());
        m.put("violatingSchedules", (double) report.violations());
        m.put("durationMs", report.elapsedNanos() / 1e6);
        m.put("expected", (double) expected);
        result.trial(new Trial(0, m, report.violations() == 0 && report.errors() == 0, null));
        result.invariant(new InvariantSummary(DESCRIPTOR.invariant(), report.schedulesExplored(), report.violations(),
                report.violations() == 0));
        result.detail("explorer", ExplorerDetails.of(report, expected));

        result.observation((report.exhaustive() ? "Exhaustive: all " : "Non-exhaustive: budget reached after ")
                + report.schedulesExplored() + " schedules at yield-point granularity.");
        result.observation(report.violations() + " of " + report.schedulesExplored() + " schedules violate the invariant.");
        report.outcomeCounts().forEach((k, v) -> result.observation("Outcome \"" + k + "\": " + v + " schedules."));
        if (report.errors() > 0) {
            result.observation("Explorer errors: " + report.errors() + " (" + report.firstError() + ").");
        }
        if (!report.exhaustive()) {
            result.observation("Unexplored schedules may contain violations; no claim is made about them.");
        }
        return result.build();
    }
}
