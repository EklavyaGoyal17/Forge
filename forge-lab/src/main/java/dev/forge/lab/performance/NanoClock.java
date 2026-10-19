package dev.forge.lab.performance;

import java.util.concurrent.locks.LockSupport;

/** Monotonic time source used by the load generator; replaceable by a virtual clock in tests. */
public interface NanoClock {

    long now();

    /** Returns at or after {@code deadlineNanos}. Real clocks may overshoot (OS timer granularity). */
    void sleepUntil(long deadlineNanos) throws InterruptedException;

    NanoClock SYSTEM = new NanoClock() {
        @Override
        public long now() {
            return System.nanoTime();
        }

        @Override
        public void sleepUntil(long deadlineNanos) throws InterruptedException {
            long remaining;
            while ((remaining = deadlineNanos - System.nanoTime()) > 0) {
                LockSupport.parkNanos(remaining);
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
            }
        }
    };
}
