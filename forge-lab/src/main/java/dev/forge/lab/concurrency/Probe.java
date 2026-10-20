package dev.forge.lab.concurrency;

/**
 * Instrumentation hooks placed immediately before every shared-memory or synchronization action in
 * concurrency scenarios.
 *
 * <p>The same scenario code runs in two harnesses:
 * <ul>
 *   <li><b>Stress mode</b> uses {@link #NONE}: every method is empty, the JIT inlines the calls away,
 *       and the code runs at full speed on real cores.</li>
 *   <li><b>Explorer mode</b> uses {@link ControlledExecution}'s probe: each "before" hook is a
 *       yield point at which the calling thread parks until the controlled scheduler selects it.</li>
 * </ul>
 *
 * <p>The methods take primitives and string literals only, so the no-op probe never allocates on
 * the hot path.
 */
public interface Probe {

    /** Before reading shared variable {@code target}. */
    void read(String target);

    /** Annotates the preceding read with the value that was observed (does not yield). */
    void value(long observed);

    /** Before writing {@code value} to shared variable {@code target}. */
    void write(String target, long value);

    /** Before compare-and-set of {@code target} from {@code expected} to {@code update}. */
    void cas(String target, long expected, long update);

    /** Annotates the preceding CAS with its result (does not yield). */
    void casResult(boolean success);

    /** Before an indivisible read-modify-write such as {@code LongAdder.increment()}. */
    void atomic(String target, String operation);

    /** Before a blocking lock acquisition (monitor entry or {@code Lock.lock()}). */
    void acquire(String lock);

    /** Before a non-blocking {@code tryLock()}. */
    void tryAcquire(String lock);

    /** Annotates the preceding tryAcquire with its result (does not yield). */
    void acquired(boolean success);

    /** Before releasing a lock (monitor exit or {@code Lock.unlock()}). */
    void release(String lock);

    /**
     * Marks that the calling thread just made progress (e.g. entered its critical section). Does not
     * yield; liveness checking ({@link LivenessExplorer}) uses it to tell progress from spinning.
     */
    void progress(String what);

    Probe NONE = new Probe() {
        @Override public void read(String target) { }
        @Override public void value(long observed) { }
        @Override public void write(String target, long value) { }
        @Override public void cas(String target, long expected, long update) { }
        @Override public void casResult(boolean success) { }
        @Override public void atomic(String target, String operation) { }
        @Override public void acquire(String lock) { }
        @Override public void tryAcquire(String lock) { }
        @Override public void acquired(boolean success) { }
        @Override public void release(String lock) { }
        @Override public void progress(String what) { }
    };
}
