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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongConsumer;

/**
 * Transfers between accounts, each guarded by its own lock. Taking two locks in an order that
 * depends on the transfer direction creates a lock-ordering cycle.
 *
 * <p>Invariants: (1) the sum of all balances never changes; (2) every trial finishes (no deadlock,
 * no livelock) within its time limit.
 */
public final class DeadlockScenario implements Scenario {

    public static final String ID = "concurrency.deadlock";
    private static final String SRC = DeadlockScenario.class.getName() + "#";
    static final long INITIAL_BALANCE = 1_000;

    static final class Account {
        final int id;
        final String lockName;
        final ReentrantLock lock = new ReentrantLock();
        long balance = INITIAL_BALANCE;

        Account(int id) {
            this.id = id;
            this.lockName = "account-" + (char) ('A' + id);
        }
    }

    interface Transfers {
        /** Moves one unit; returns the number of failed lock attempts (tryLock variant), else 0. */
        long transfer(Account from, Account to, Probe probe) throws InterruptedException;
    }

    // forge:snippet nested-locks
    static final class NestedLocks implements Transfers {
        @Override
        public long transfer(Account from, Account to, Probe probe) throws InterruptedException {
            probe.acquire(from.lockName);
            from.lock.lockInterruptibly();         // lock order depends on transfer direction...
            try {
                probe.acquire(to.lockName);
                to.lock.lockInterruptibly();       // ...so A→B and B→A can each hold one lock and wait forever
                try {
                    from.balance -= 1;
                    to.balance += 1;
                } finally {
                    probe.release(to.lockName);
                    to.lock.unlock();
                }
            } finally {
                probe.release(from.lockName);
                from.lock.unlock();
            }
            return 0;
        }
    }
    // forge:end

    // forge:snippet ordered-locks
    static final class OrderedLocks implements Transfers {
        @Override
        public long transfer(Account from, Account to, Probe probe) throws InterruptedException {
            Account first = from.id < to.id ? from : to;    // global order: lower id first
            Account second = from.id < to.id ? to : from;   // no cycle in the wait-for graph is possible
            probe.acquire(first.lockName);
            first.lock.lockInterruptibly();
            try {
                probe.acquire(second.lockName);
                second.lock.lockInterruptibly();
                try {
                    from.balance -= 1;
                    to.balance += 1;
                } finally {
                    probe.release(second.lockName);
                    second.lock.unlock();
                }
            } finally {
                probe.release(first.lockName);
                first.lock.unlock();
            }
            return 0;
        }
    }
    // forge:end

    // forge:snippet trylock-backoff
    static final class TryLockBackoff implements Transfers {
        private final SplittableRandom random;
        private final LongConsumer pause;          // LockSupport::parkNanos (stress) or no-op (explorer)

        TryLockBackoff(long seed, LongConsumer pause) {
            this.random = new SplittableRandom(seed);
            this.pause = pause;
        }

        @Override
        public long transfer(Account from, Account to, Probe probe) throws InterruptedException {
            long failures = 0;
            while (true) {
                probe.tryAcquire(from.lockName);
                boolean gotFrom = from.lock.tryLock();
                probe.acquired(gotFrom);
                if (gotFrom) {
                    try {
                        probe.tryAcquire(to.lockName);
                        boolean gotTo = to.lock.tryLock();
                        probe.acquired(gotTo);
                        if (gotTo) {
                            try {
                                from.balance -= 1;
                                to.balance += 1;
                                return failures;
                            } finally {
                                probe.release(to.lockName);
                                to.lock.unlock();
                            }
                        }
                    } finally {
                        probe.release(from.lockName);
                        from.lock.unlock();
                    }
                }
                failures++;                         // back off for a random time so that two threads
                pause.accept(random.nextLong(1_000, 50_000));  // stop retrying in lockstep
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
            }
        }
    }
    // forge:end

    private static Transfers transfers(String variant, long seed, boolean explore) {
        return switch (variant) {
            case "nested-locks" -> new NestedLocks();
            case "ordered-locks" -> new OrderedLocks();
            case "trylock-backoff" -> new TryLockBackoff(seed, explore ? nanos -> { } : LockSupport::parkNanos);
            default -> throw new IllegalArgumentException("unknown variant " + variant);
        };
    }

    private static final ScenarioDescriptor DESCRIPTOR = new ScenarioDescriptor(
            ID, 1, Lab.CONCURRENCY,
            "Deadlock: lock ordering",
            "Threads transfer money between accounts, locking both accounts. Watch nested locking deadlock, then remove the cycle with a global lock order or avoid blocking with tryLock.",
            "Under what conditions does nested locking deadlock, and which strategy removes the risk without losing correctness?",
            "Total balance is conserved and every trial completes within its time limit.",
            List.of(
                    ParameterSpec.choice("mode", "Mode", "stress", List.of("stress", "explore"),
                            "stress: real threads with JVM deadlock detection. explore: controlled scheduling finds the deadlocking interleaving deterministically."),
                    ParameterSpec.integer("accounts", "Accounts", 2, 2, 16, "accounts",
                            "Fewer accounts → more lock overlap → deadlock sooner.").onlyFor("stress"),
                    ParameterSpec.integer("threads", "Threads", 4, 2, 32, "threads", "Concurrent transferring threads.").onlyFor("stress"),
                    ParameterSpec.integer("transfersPerThread", "Transfers per thread", 20_000, 1, 1_000_000, "ops",
                            "Random transfers each thread attempts per trial.").onlyFor("stress"),
                    ParameterSpec.integer("trialTimeoutMs", "Trial time limit", 3_000, 100, 20_000, "ms",
                            "A trial that has not finished by then counts as not completing.").onlyFor("stress"),
                    ParameterSpec.integer("exploreTransfers", "Transfers per thread", 1, 1, 2, "ops",
                            "T1 moves A→B, T2 moves B→A, this many times each.").onlyFor("explore"),
                    ParameterSpec.choice("strategy", "Search strategy", "exhaustive", List.of("exhaustive", "random"),
                            "exhaustive: DFS over all schedules (bounded by the budget). random: seeded random walks.").onlyFor("explore"),
                    ParameterSpec.integer("maxSchedules", "Schedule budget", 5_000, 1, 50_000, "schedules",
                            "Upper bound on executions.").onlyFor("explore"),
                    ParameterSpec.integer("maxSteps", "Step bound", 200, 10, 5_000, "steps",
                            "Schedules longer than this are cut off and reported as possible livelock.").onlyFor("explore")),
            List.of(
                    new VariantSpec("nested-locks", "Nested locks (direction order)", "Lock source, then destination. Opposite transfers lock in opposite orders.", false, SRC + "nested-locks"),
                    new VariantSpec("ordered-locks", "Global lock order", "Always lock the lower account id first, whatever the direction.", true, SRC + "ordered-locks"),
                    new VariantSpec("trylock-backoff", "tryLock + random backoff", "Never block while holding a lock; release and retry after a random pause.", true, SRC + "trylock-backoff")),
            List.of(
                    new MetricSpec("throughputKPerSec", "Throughput", "k transfers/s", Direction.HIGHER_IS_BETTER, "Completed transfers per second of trial time."),
                    new MetricSpec("durationMs", "Duration", "ms", Direction.LOWER_IS_BETTER, "Wall-clock time until all threads finished or the trial was aborted."),
                    new MetricSpec("deadlocked", "Deadlocked", "0/1", Direction.LOWER_IS_BETTER, "1 if the JVM reported a deadlock cycle."),
                    new MetricSpec("completedTransfers", "Completed transfers", "", Direction.NEUTRAL, "Transfers that committed."),
                    new MetricSpec("failedLockAttempts", "Failed tryLock attempts", "", Direction.LOWER_IS_BETTER, "tryLock variant only: retries caused by contention."),
                    new MetricSpec("conserved", "Balance conserved", "0/1", Direction.NEUTRAL, "1 if the total balance is unchanged."),
                    new MetricSpec("schedulesExplored", "Schedules explored", "", Direction.NEUTRAL, "Explorer mode."),
                    new MetricSpec("violatingSchedules", "Violating schedules", "", Direction.LOWER_IS_BETTER, "Explorer mode: deadlocks, step-bound hits, or conservation failures.")),
            "throughputKPerSec",
            new ScenarioDescriptor.TrialPolicy(10, 100, 1, 10),
            180,
            """
            A deadlock needs four conditions at once (Coffman): mutual exclusion, hold-and-wait, no preemption, and a \
            circular wait. Nested locking in transfer-direction order creates the circular wait: T1 holds A and waits \
            for B while T2 holds B and waits for A. A **global lock order** makes a cycle impossible because every \
            thread acquires locks in the same sequence. **tryLock with backoff** removes hold-and-wait instead — a thread \
            that cannot get the second lock releases the first — but trades deadlock for the possibility of livelock \
            (threads that keep retrying in lockstep) and wasted work, which the random backoff makes unlikely, not impossible.

            Stress mode detects deadlocks with `ThreadMXBean.findDeadlockedThreads()` and attaches the JVM's own \
            thread dump of the cycle. Explorer mode finds the deadlocking schedule deterministically.""",
            List.of(
                    "Whether stress mode deadlocks in a given trial depends on timing; few accounts and many threads make it near-certain, many accounts make it rare (but still possible).",
                    "Explorer mode treats the tryLock backoff pause as a no-op: the controlled scheduler, not the clock, decides who runs. Schedules that hit the step bound are adversarial livelocks that real random backoff makes improbable.",
                    "findDeadlockedThreads only detects cycles on monitors and ownable synchronizers such as ReentrantLock; it does not detect livelock or starvation.",
                    "Throughput of the ordered and tryLock variants is not comparable across different account counts: contention changes with the account count.",
                    "On Windows, LockSupport.parkNanos for a few microseconds usually sleeps for about a millisecond or more (timer granularity), so the tryLock variant's backoff is much longer there than its parameters suggest."));

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
        int nAccounts = spec.intParam("accounts");
        int nThreads = spec.intParam("threads");
        int perThread = spec.intParam("transfersPerThread");
        long trialTimeoutNanos = spec.longParam("trialTimeoutMs") * 1_000_000L;
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        ScenarioResult.Builder result = ScenarioResult.builder();
        int total = spec.warmupTrials() + spec.trials();
        boolean dumpAttached = false;
        int deadlockedTrials = 0;

        for (int t = 0; t < total; t++) {
            ctx.checkCancelled();
            boolean warmup = t < spec.warmupTrials();
            Account[] accounts = new Account[nAccounts];
            for (int i = 0; i < nAccounts; i++) {
                accounts[i] = new Account(i);
            }
            AtomicLong completed = new AtomicLong();
            AtomicLong failures = new AtomicLong();
            CountDownLatch ready = new CountDownLatch(nThreads);
            CountDownLatch gate = new CountDownLatch(1);
            Thread[] workers = new Thread[nThreads];
            for (int i = 0; i < nThreads; i++) {
                long threadSeed = spec.seed() * 1_000_003L + t * 1_009L + i;
                Transfers impl = transfers(spec.variant(), threadSeed, false);
                SplittableRandom rnd = new SplittableRandom(threadSeed);
                workers[i] = new Thread(() -> {
                    ready.countDown();
                    try {
                        gate.await();
                        for (int k = 0; k < perThread; k++) {
                            int a = rnd.nextInt(nAccounts);
                            int b = rnd.nextInt(nAccounts - 1);
                            if (b >= a) {
                                b++;
                            }
                            failures.addAndGet(impl.transfer(accounts[a], accounts[b], Probe.NONE));
                            completed.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        // aborted by the deadlock monitor or the trial time limit
                    }
                }, "transfer-" + i);
                workers[i].setDaemon(true);
                workers[i].start();
            }
            ready.await();
            long begin = System.nanoTime();
            gate.countDown();

            long[] deadlockedIds = null;
            boolean timedOut = false;
            while (true) {
                boolean allDone = true;
                for (Thread w : workers) {
                    if (w.isAlive()) {
                        allDone = false;
                        break;
                    }
                }
                if (allDone) {
                    break;
                }
                deadlockedIds = mx.findDeadlockedThreads();
                if (deadlockedIds != null) {
                    break;
                }
                long now = System.nanoTime();
                if (now - begin > trialTimeoutNanos || now - ctx.deadlineNanos() > 0) {
                    timedOut = true;
                    break;
                }
                Thread.sleep(5);
            }
            long elapsed = System.nanoTime() - begin;

            if (deadlockedIds != null && !dumpAttached && !warmup) {
                ctx.artifact("deadlock-thread-dump-trial-" + (t - spec.warmupTrials()) + ".txt", "text/plain",
                        formatDump(mx.getThreadInfo(deadlockedIds, true, true)));
                dumpAttached = true;
            }
            for (Thread w : workers) {
                w.interrupt();
            }
            for (Thread w : workers) {
                w.join(2_000);
            }

            long sum = 0;
            for (Account a : accounts) {
                a.lock.lock();
                try {
                    sum += a.balance;
                } finally {
                    a.lock.unlock();
                }
            }
            boolean conserved = sum == nAccounts * INITIAL_BALANCE;
            boolean deadlocked = deadlockedIds != null;
            if (warmup) {
                ctx.progress(t + 1, total, "warm-up");
                continue;
            }
            if (deadlocked) {
                deadlockedTrials++;
            }
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("deadlocked", deadlocked ? 1.0 : 0.0);
            m.put("completedTransfers", (double) completed.get());
            m.put("failedLockAttempts", (double) failures.get());
            m.put("conserved", conserved ? 1.0 : 0.0);
            m.put("durationMs", elapsed / 1e6);
            m.put("throughputKPerSec", completed.get() / (elapsed / 1e9) / 1e3);
            boolean finished = !deadlocked && !timedOut && completed.get() == (long) nThreads * perThread;
            String note = deadlocked ? "deadlock detected after " + completed.get() + " transfers"
                    : timedOut ? "did not finish within the trial time limit" : null;
            result.trial(new Trial(t - spec.warmupTrials(), m, finished && conserved, note));
            ctx.progress(t + 1, total, "measured");
        }

        InvariantSummary inv = InvariantSummary.of(DESCRIPTOR.invariant(), result.trials());
        result.invariant(inv);
        result.observation("Deadlock detected by the JVM in " + deadlockedTrials + " of " + spec.trials() + " measured trials.");
        result.observation("Invariant violated in " + inv.violations() + " of " + inv.checked() + " measured trials.");
        if (dumpAttached) {
            result.observation("The JVM thread dump of the first deadlock cycle is attached as an artifact.");
        }
        return result.build();
    }

    private static String formatDump(ThreadInfo[] infos) {
        StringBuilder sb = new StringBuilder("Deadlock cycle reported by ThreadMXBean.findDeadlockedThreads()\n\n");
        for (ThreadInfo ti : infos) {
            if (ti == null) {
                continue;
            }
            sb.append('"').append(ti.getThreadName()).append("\" state=").append(ti.getThreadState()).append('\n');
            sb.append("    waiting for ").append(ti.getLockName())
                    .append(" owned by \"").append(ti.getLockOwnerName()).append("\"\n");
            for (StackTraceElement e : ti.getStackTrace()) {
                sb.append("        at ").append(e).append('\n');
            }
            for (var s : ti.getLockedSynchronizers()) {
                sb.append("    holds ").append(s).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- explorer mode

    private record ExploreState(Account a, Account b, Transfers impl) {
    }

    private ScenarioResult explore(RunSpec spec, RunContext ctx) {
        int transfers = spec.intParam("exploreTransfers");
        int budget = spec.intParam("maxSchedules");
        int maxSteps = spec.intParam("maxSteps");
        boolean random = spec.stringParam("strategy").equals("random");
        ctx.info("explore (" + (random ? "random sampling" : "exhaustive DFS") + "): T1 A→B, T2 B→A, "
                + transfers + " transfer(s) each, budget " + budget);

        java.util.function.Supplier<ExploreState> fresh =
                () -> new ExploreState(new Account(0), new Account(1), transfers(spec.variant(), spec.seed(), true));
        java.util.function.Function<ExploreState, List<NamedTask>> tasks = s -> List.of(
                new NamedTask("T1", probe -> repeat(transfers, () -> s.impl().transfer(s.a(), s.b(), probe))),
                new NamedTask("T2", probe -> repeat(transfers, () -> s.impl().transfer(s.b(), s.a(), probe))));
        java.util.function.Function<ExploreState, InterleavingExplorer.Check> check = s -> {
            long sum = s.a().balance + s.b().balance;
            return new InterleavingExplorer.Check(sum == 2 * INITIAL_BALANCE,
                    "completed, A=" + s.a().balance + " B=" + s.b().balance);
        };
        InterleavingExplorer.Report report = random
                ? InterleavingExplorer.sample(fresh, tasks, check, budget, maxSteps, ctx::checkCancelled, spec.seed())
                : InterleavingExplorer.explore(fresh, tasks, check, budget, maxSteps, ctx::checkCancelled);

        ScenarioResult.Builder result = ScenarioResult.builder();
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("schedulesExplored", (double) report.schedulesExplored());
        m.put("violatingSchedules", (double) report.violations());
        m.put("durationMs", report.elapsedNanos() / 1e6);
        result.trial(new Trial(0, m, report.violations() == 0 && report.errors() == 0, null));
        result.invariant(new InvariantSummary(DESCRIPTOR.invariant(), report.schedulesExplored(), report.violations(),
                report.violations() == 0));
        Map<String, Object> details = new LinkedHashMap<>(ExplorerDetails.of(report, 2 * INITIAL_BALANCE));
        details.put("strategy", random ? "random" : "exhaustive");
        result.detail("explorer", details);

        if (random) {
            result.observation("Random sampling of " + report.schedulesExplored() + " schedules (seed " + spec.seed() + "); not exhaustive.");
        } else {
            result.observation((report.exhaustive() ? "Exhaustive: all " : "Non-exhaustive: budget reached after ")
                    + report.schedulesExplored() + " schedules at yield-point granularity.");
        }
        result.observation(report.deadlocks() + " schedules deadlock; " + report.stepLimitHits()
                + " exceed the " + maxSteps + "-step bound.");
        if (report.errors() > 0) {
            result.observation("Explorer errors: " + report.errors() + " (" + report.firstError() + ").");
        }
        return result.build();
    }

    @FunctionalInterface
    private interface Action {
        void run() throws InterruptedException;
    }

    private static void repeat(int times, Action action) {
        try {
            for (int i = 0; i < times; i++) {
                action.run();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
