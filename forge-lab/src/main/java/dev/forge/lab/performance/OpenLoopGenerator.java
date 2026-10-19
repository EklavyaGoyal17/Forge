package dev.forge.lab.performance;

import dev.forge.lab.performance.LatencyRecorder.Outcome;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Open-model (constant arrival rate) load generator.
 *
 * <p>Requests are due on a fixed schedule regardless of how fast earlier ones complete — the way
 * independent users arrive at a service. (A closed model, where N virtual users each wait for a
 * response before sending again, slows its own arrival rate when the server slows down and so
 * hides overload.) At most {@code maxInFlight} requests are outstanding, like a client connection
 * pool; when none is free the generator waits, and that wait counts towards latency because
 * latency is measured from the intended send time (see {@link LatencyRecorder}).
 *
 * <p>A request that cannot be sent before {@code intended + timeout} is recorded as a timeout
 * without being sent. All waiting is bounded, so a trial ends at most about one request timeout
 * after its last intended send time.
 */
public final class OpenLoopGenerator {

    /** Extra time after the last request's timeout before giving up on stragglers. */
    public static final long DRAIN_GRACE_NANOS = 500_000_000L;

    private OpenLoopGenerator() {
    }

    /** Sends request {@code index}; completes with the HTTP status or exceptionally. */
    public interface Transport {
        CompletableFuture<Integer> send(long index, boolean measured, long timeoutNanos);
    }

    public record Plan(double ratePerSecond, long warmupNanos, long measureNanos, long timeoutNanos,
                       int maxInFlight, long windowNanos) {
        public Plan {
            if (maxInFlight < 1 || timeoutNanos <= 0) {
                throw new IllegalArgumentException("maxInFlight and timeout must be positive");
            }
        }
    }

    /** @param unfinished requests still outstanding when the drain deadline passed (not in the recorder). */
    public record Result(LatencyRecorder recorder, long scheduled, long unfinished) {
    }

    public static Result run(Plan plan, NanoClock clock, Transport transport, Runnable checkCancelled)
            throws InterruptedException {
        long start = clock.now();
        LatencyRecorder rec = new LatencyRecorder(start, plan.warmupNanos(), plan.measureNanos(),
                plan.timeoutNanos() + DRAIN_GRACE_NANOS, plan.windowNanos());
        ArrivalSchedule schedule = new ArrivalSchedule(start, plan.ratePerSecond());
        long total = schedule.countWithin(plan.warmupNanos() + plan.measureNanos());
        Semaphore permits = new Semaphore(plan.maxInFlight());

        for (long i = 0; i < total; i++) {
            checkCancelled.run();
            long intended = schedule.intendedNanos(i);       // fixed by the schedule, not by earlier responses
            if (clock.now() - intended < 0) {
                clock.sleepUntil(intended);
                rec.timerLag(intended, clock.now());
            }
            long deadline = intended + plan.timeoutNanos();
            if (!acquire(permits, deadline, clock)) {         // no free connection before the deadline
                rec.record(intended, -1, clock.now(), Outcome.TIMEOUT);
                continue;
            }
            long sent = clock.now();
            if (sent - deadline >= 0) {
                permits.release();
                rec.record(intended, -1, sent, Outcome.TIMEOUT);
                continue;
            }
            CompletableFuture<Integer> response;
            try {
                response = transport.send(i, rec.isMeasured(intended), deadline - sent);
            } catch (RuntimeException e) {
                permits.release();
                rec.record(intended, sent, clock.now(), Outcome.FAILED);
                continue;
            }
            response.whenComplete((status, error) -> {
                long completed = clock.now();
                try {
                    // latency = completed − intended; completed − sent is recorded only for comparison
                    rec.record(intended, sent, completed, classify(status, error));
                } finally {
                    permits.release();
                }
            });
        }

        long lastIntended = total == 0 ? start : schedule.intendedNanos(total - 1);
        long drainDeadline = lastIntended + plan.timeoutNanos() + DRAIN_GRACE_NANOS;
        long remaining = drainDeadline - clock.now();
        boolean drained = remaining > 0
                ? permits.tryAcquire(plan.maxInFlight(), remaining, TimeUnit.NANOSECONDS)
                : permits.tryAcquire(plan.maxInFlight());
        long unfinished = drained ? 0 : plan.maxInFlight() - permits.availablePermits();
        return new Result(rec, total, unfinished);
    }

    private static boolean acquire(Semaphore permits, long deadline, NanoClock clock) throws InterruptedException {
        if (permits.tryAcquire()) {
            return true;
        }
        long remaining = deadline - clock.now();
        return remaining > 0 && permits.tryAcquire(remaining, TimeUnit.NANOSECONDS);
    }

    static Outcome classify(Integer status, Throwable error) {
        Throwable e = error;
        while ((e instanceof CompletionException || e instanceof ExecutionException) && e.getCause() != null) {
            e = e.getCause();
        }
        if (e != null) {
            return e instanceof java.net.http.HttpTimeoutException || e instanceof TimeoutException
                    ? Outcome.TIMEOUT : Outcome.FAILED;
        }
        return status != null && status == 200 ? Outcome.OK : Outcome.HTTP_ERROR;
    }
}
