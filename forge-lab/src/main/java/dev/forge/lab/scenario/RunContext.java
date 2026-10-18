package dev.forge.lab.scenario;

import java.nio.file.Path;

/** Services the runner provides to an executing scenario. */
public interface RunContext {

    /** Records a lifecycle/log event that is persisted and shown on the run timeline. */
    void event(String level, String message);

    default void info(String message) {
        event("INFO", message);
    }

    default void warn(String message) {
        event("WARN", message);
    }

    /** Reports progress, e.g. completed trials out of total. */
    void progress(long done, long total, String phase);

    /** Attaches a raw text artifact (thread dump, trace, tool log) to the run. */
    void artifact(String name, String contentType, String content);

    /** Private per-run directory; the only place a scenario may write files. */
    Path workDir();

    /** Nanos from {@link System#nanoTime()} after which the scenario should stop producing new work. */
    long deadlineNanos();

    /** Throws {@link CancelledException} if the deadline passed or the thread was interrupted. */
    default void checkCancelled() {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadlineNanos() > 0) {
            throw new CancelledException("run cancelled or deadline exceeded");
        }
    }

    final class CancelledException extends RuntimeException {
        public CancelledException(String message) {
            super(message);
        }
    }

    /** A context that discards everything; useful in tests. */
    static RunContext noop(Path workDir) {
        long deadline = System.nanoTime() + 3_600_000_000_000L;
        return new RunContext() {
            @Override public void event(String level, String message) { }
            @Override public void progress(long done, long total, String phase) { }
            @Override public void artifact(String name, String contentType, String content) { }
            @Override public Path workDir() { return workDir; }
            @Override public long deadlineNanos() { return deadline; }
        };
    }
}
