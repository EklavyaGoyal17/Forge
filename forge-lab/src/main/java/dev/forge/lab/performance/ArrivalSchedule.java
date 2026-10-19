package dev.forge.lab.performance;

/**
 * Intended send times of a constant-arrival-rate (open model) load.
 *
 * <p>Request {@code i} is due at {@code start + floor(i · 10⁹ / rate)} nanoseconds. Times are
 * computed from the index, never by repeatedly adding a rounded interval, so a non-integer
 * interval (e.g. 3 req/s = 333 333 333.3 ns) does not drift: request 3 is due exactly one second
 * after request 0.
 */
public record ArrivalSchedule(long startNanos, double ratePerSecond) {

    public ArrivalSchedule {
        if (!(ratePerSecond > 0) || Double.isInfinite(ratePerSecond)) {
            throw new IllegalArgumentException("rate must be positive and finite");
        }
    }

    /** Offset of request {@code index} from the start, in nanoseconds. */
    public long offsetNanos(long index) {
        return (long) Math.floor(index * 1e9 / ratePerSecond);
    }

    public long intendedNanos(long index) {
        return startNanos + offsetNanos(index);
    }

    /** Number of requests whose intended time falls in {@code [start, start + spanNanos)}. */
    public long countWithin(long spanNanos) {
        if (spanNanos <= 0) {
            return 0;
        }
        long n = (long) Math.ceil(spanNanos * ratePerSecond / 1e9);
        // correct floating-point edge cases at the boundary
        while (n > 0 && offsetNanos(n - 1) >= spanNanos) {
            n--;
        }
        while (offsetNanos(n) < spanNanos) {
            n++;
        }
        return n;
    }
}
