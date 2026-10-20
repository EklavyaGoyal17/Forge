package dev.forge.lab.concurrency;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs a set of tasks on <em>real</em> platform threads while allowing exactly one of them to make
 * progress between consecutive {@link Probe} yield points.
 *
 * <h2>How it works</h2>
 * Every worker parks at each yield point and signals arrival. The driver (the calling thread)
 * waits until every live worker has arrived, computes which workers are <em>enabled</em> (a worker
 * whose next action is acquiring a lock held by another worker is not), asks the {@link Chooser}
 * which enabled worker goes next, and releases exactly that worker. It then waits for that worker
 * to arrive at its next yield point or terminate. The result is a fully deterministic interleaving
 * at yield-point granularity.
 *
 * <p>Lock state is tracked in a model that is updated when a lock action is granted. Because every
 * lock action is a yield point and only one worker runs at a time, the model and the real lock
 * always agree when the driver inspects them — so a worker is never released into a real lock that
 * would block it. If no live worker is enabled, the execution is a deadlock and the driver reports
 * the wait-for relation; it then aborts all workers (they never actually entered the real locks
 * they were waiting for, so they unwind cleanly).
 *
 * <h2>Limits</h2>
 * <ul>
 *   <li>Code between yield points runs atomically from the explorer's point of view.</li>
 *   <li>Sequential consistency is assumed: Java Memory Model reorderings and stale reads of
 *       non-volatile fields are not modelled.</li>
 * </ul>
 */
public final class ControlledExecution {

    /** Returned by {@link Chooser#choose(DecisionPoint)} to end the execution here (outcome {@code STOPPED}). */
    public static final int STOP = -1;

    /** Picks which enabled worker runs next. */
    @FunctionalInterface
    public interface Chooser {
        /**
         * @param enabled indices of enabled workers, ascending
         * @return a position in {@code enabled}
         */
        int choose(List<Integer> enabled);

        /** Full-information variant; may return {@link #STOP}. Defaults to {@link #choose(List)}. */
        default int choose(DecisionPoint point) {
            return choose(point.enabled());
        }

        /**
         * Called with every scheduler state while all workers are parked, including the final one
         * (all finished, or deadlocked) — before {@link #choose(DecisionPoint)} when a choice follows.
         */
        default void observe(DecisionPoint point) {
        }
    }

    /**
     * The scheduler's view while every live worker is parked at a yield point.
     *
     * @param depth       number of steps executed so far
     * @param enabled     indices of workers that may run next, ascending
     * @param pending     each worker's next action ({@code null} once it has finished)
     * @param progress    how often each worker has called {@link Probe#progress(String)}
     * @param lockHolders lock name → index of the worker holding it, for held locks
     */
    public record DecisionPoint(int depth, List<Integer> enabled, List<String> pending, List<Integer> progress,
                                Map<String, Integer> lockHolders) {
    }

    public record NamedTask(String name, Consumer<Probe> body) {
    }

    public enum Outcome { COMPLETED, DEADLOCK, STEP_LIMIT, ERROR, STOPPED }

    public record Step(int index, String thread, String kind, String target, String detail, String outcome) {
    }

    /**
     * @param decisions for each step, the enabled worker indices and the position chosen; used by
     *                  {@link InterleavingExplorer} for backtracking.
     */
    public record Execution(Outcome outcome, List<Step> steps, List<String> waitFor, String error,
                            List<Decision> decisions) {
    }

    public record Decision(List<Integer> enabled, int chosen) {
    }

    /** Thrown inside worker threads to unwind them when an execution is aborted. */
    static final class AbortSignal extends Error {
        AbortSignal() {
            super("execution aborted by controlled scheduler", null, false, false);
        }
    }

    private static final long STALL_TIMEOUT_MS = 5_000;

    private final List<NamedTask> tasks;
    private final Chooser chooser;
    private final int maxSteps;

    private final List<Worker> workers = new ArrayList<>();
    private final Semaphore arrived = new Semaphore(0);
    private final Map<String, LockState> locks = new HashMap<>();
    private final List<MutableStep> steps = new ArrayList<>();
    private final List<Decision> decisions = new ArrayList<>();
    private final ThreadLocal<Worker> current = new ThreadLocal<>();
    private volatile boolean aborting;

    private ControlledExecution(List<NamedTask> tasks, Chooser chooser, int maxSteps) {
        this.tasks = tasks;
        this.chooser = chooser;
        this.maxSteps = maxSteps;
    }

    public static Execution run(List<NamedTask> tasks, Chooser chooser, int maxSteps) {
        return new ControlledExecution(List.copyOf(tasks), chooser, maxSteps).execute();
    }

    private Execution execute() {
        Probe probe = new ControlledProbe();
        for (int i = 0; i < tasks.size(); i++) {
            NamedTask task = tasks.get(i);
            Worker w = new Worker(i, task.name());
            workers.add(w);
            w.thread = new Thread(() -> runWorker(w, task, probe), "forge-explore-" + task.name());
            w.thread.setDaemon(true);
        }
        workers.forEach(w -> w.thread.start());

        try {
            if (!arrived.tryAcquire(workers.size(), STALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return abort(Outcome.ERROR, List.of(), "workers did not reach their first yield point");
            }
            while (true) {
                Worker failed = workers.stream().filter(w -> w.failure != null).findFirst().orElse(null);
                if (failed != null) {
                    return abort(Outcome.ERROR, List.of(), failed.name + " threw " + failed.failure);
                }
                List<Worker> live = workers.stream().filter(w -> !w.finished).toList();
                List<Integer> enabled = new ArrayList<>();
                for (Worker w : live) {
                    if (isEnabled(w)) {
                        enabled.add(w.index);
                    }
                }
                DecisionPoint point = point(enabled);
                chooser.observe(point);
                if (live.isEmpty()) {
                    return finish(Outcome.COMPLETED, List.of(), null);
                }
                if (enabled.isEmpty()) {
                    return abort(Outcome.DEADLOCK, describeWaits(live), null);
                }
                if (steps.size() >= maxSteps) {
                    return abort(Outcome.STEP_LIMIT, List.of(), "step bound " + maxSteps + " reached");
                }
                int pos = chooser.choose(point);
                if (pos == STOP) {
                    return abort(Outcome.STOPPED, List.of(), null);
                }
                if (pos < 0 || pos >= enabled.size()) {
                    throw new IllegalStateException("chooser returned invalid position " + pos);
                }
                decisions.add(new Decision(List.copyOf(enabled), pos));
                Worker next = workers.get(enabled.get(pos));
                grant(next);
                if (!arrived.tryAcquire(STALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    return abort(Outcome.ERROR, List.of(), next.name + " did not reach a yield point within "
                            + STALL_TIMEOUT_MS + " ms (lock model out of sync or uninstrumented blocking)");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return abort(Outcome.ERROR, List.of(), "driver interrupted");
        }
    }

    private DecisionPoint point(List<Integer> enabled) {
        List<String> pending = new ArrayList<>(workers.size());
        List<Integer> progress = new ArrayList<>(workers.size());
        for (Worker w : workers) {
            PendingAction a = w.pending;
            pending.add(w.finished || a == null ? null : a.detail);
            progress.add(w.progress);
        }
        Map<String, Integer> holders = new java.util.TreeMap<>();
        locks.forEach((name, s) -> {
            if (s.holder != null) {
                holders.put(name, s.holder.index);
            }
        });
        return new DecisionPoint(steps.size(), List.copyOf(enabled), java.util.Collections.unmodifiableList(pending),
                List.copyOf(progress), java.util.Collections.unmodifiableMap(holders));
    }

    private boolean isEnabled(Worker w) {
        PendingAction a = w.pending;
        if (a == null) {
            return false;
        }
        if (a.kind.equals("ACQUIRE")) {
            LockState s = locks.get(a.target);
            return s == null || s.holder == null || s.holder == w;
        }
        return true;
    }

    /** Applies the model effect of the granted action, records the step, and releases the worker. */
    private void grant(Worker w) {
        PendingAction a = w.pending;
        String outcome = null;
        switch (a.kind) {
            case "ACQUIRE" -> {
                LockState s = locks.computeIfAbsent(a.target, k -> new LockState());
                s.holder = w;
                s.count++;
            }
            case "TRY_ACQUIRE" -> {
                LockState s = locks.computeIfAbsent(a.target, k -> new LockState());
                if (s.holder == null || s.holder == w) {
                    s.holder = w;
                    s.count++;
                } else {
                    outcome = "held by " + s.holder.name;
                }
            }
            case "RELEASE" -> {
                LockState s = locks.get(a.target);
                if (s == null || s.holder != w) {
                    w.failure = new IllegalMonitorStateException(w.name + " released " + a.target + " without holding it");
                } else if (--s.count == 0) {
                    s.holder = null;
                }
            }
            default -> {
            }
        }
        MutableStep step = new MutableStep(steps.size(), w.name, a.kind, a.target, a.detail);
        step.outcome = outcome;
        steps.add(step);
        w.lastStep = step;
        w.pending = null;
        w.go.release();
    }

    private List<String> describeWaits(List<Worker> live) {
        List<String> out = new ArrayList<>();
        for (Worker w : live) {
            PendingAction a = w.pending;
            if (a != null && a.kind.equals("ACQUIRE")) {
                LockState s = locks.get(a.target);
                out.add(w.name + " waits for " + a.target + " held by " + (s == null || s.holder == null ? "?" : s.holder.name));
            }
        }
        return out;
    }

    private Execution abort(Outcome outcome, List<String> waits, String error) {
        aborting = true;
        for (Worker w : workers) {
            w.go.release(1_000);
        }
        for (Worker w : workers) {
            try {
                w.thread.join(STALL_TIMEOUT_MS);
                if (w.thread.isAlive()) {
                    w.thread.interrupt();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return finish(outcome, waits, error);
    }

    private Execution finish(Outcome outcome, List<String> waits, String error) {
        if (outcome == Outcome.COMPLETED) {
            for (Worker w : workers) {
                try {
                    w.thread.join(STALL_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        List<Step> frozen = steps.stream()
                .map(s -> new Step(s.index, s.thread, s.kind, s.target, s.detail, s.outcome))
                .toList();
        return new Execution(outcome, frozen, List.copyOf(waits), error, List.copyOf(decisions));
    }

    private void runWorker(Worker w, NamedTask task, Probe probe) {
        current.set(w);
        try {
            task.body().accept(probe);
        } catch (AbortSignal ignored) {
            // unwinding an aborted execution
        } catch (Throwable t) {
            w.failure = t;
        } finally {
            w.finished = true;
            if (!aborting) {
                arrived.release();
            }
        }
    }

    private void yieldPoint(String kind, String target, String detail) {
        Worker w = current.get();
        if (w == null) {
            return; // not a controlled thread (e.g. setup code on the driver)
        }
        if (aborting) {
            throw new AbortSignal();
        }
        w.pending = new PendingAction(kind, target, detail);
        arrived.release();
        w.go.acquireUninterruptibly();
        if (aborting) {
            throw new AbortSignal();
        }
    }

    private void annotate(String text) {
        Worker w = current.get();
        if (w != null && w.lastStep != null) {
            w.lastStep.outcome = w.lastStep.outcome == null ? text : w.lastStep.outcome + "; " + text;
        }
    }

    private final class ControlledProbe implements Probe {
        @Override public void read(String target) { yieldPoint("READ", target, "read " + target); }
        @Override public void value(long observed) { annotate("saw " + observed); }
        @Override public void write(String target, long value) { yieldPoint("WRITE", target, "write " + target + " = " + value); }
        @Override public void cas(String target, long expected, long update) {
            yieldPoint("CAS", target, "CAS " + target + ": " + expected + " → " + update);
        }
        @Override public void casResult(boolean success) { annotate(success ? "succeeded" : "failed (value changed)"); }
        @Override public void atomic(String target, String operation) { yieldPoint("ATOMIC", target, operation + " " + target); }
        @Override public void acquire(String lock) { yieldPoint("ACQUIRE", lock, "lock " + lock); }
        @Override public void tryAcquire(String lock) { yieldPoint("TRY_ACQUIRE", lock, "tryLock " + lock); }
        @Override public void acquired(boolean success) { annotate(success ? "acquired" : "not acquired"); }
        @Override public void release(String lock) { yieldPoint("RELEASE", lock, "unlock " + lock); }
        @Override public void progress(String what) {
            Worker w = current.get();
            if (w != null) {
                w.progress++;
                annotate("progress: " + what);
            }
        }
    }

    private static final class Worker {
        final int index;
        final String name;
        final Semaphore go = new Semaphore(0);
        Thread thread;
        volatile PendingAction pending;
        volatile boolean finished;
        volatile Throwable failure;
        volatile MutableStep lastStep;
        volatile int progress;

        Worker(int index, String name) {
            this.index = index;
            this.name = name;
        }
    }

    private record PendingAction(String kind, String target, String detail) {
    }

    private static final class LockState {
        Worker holder;
        int count;
    }

    private static final class MutableStep {
        final int index;
        final String thread;
        final String kind;
        final String target;
        final String detail;
        volatile String outcome;

        MutableStep(int index, String thread, String kind, String target, String detail) {
            this.index = index;
            this.thread = thread;
            this.kind = kind;
            this.target = target;
            this.detail = detail;
        }
    }
}
