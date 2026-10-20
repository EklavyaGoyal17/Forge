package dev.forge.lab.scenarios.failure;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * A count-based circuit breaker (the state machine used by Resilience4j and Hystrix, without their
 * extras), small enough to read in one sitting.
 *
 * <ul>
 *   <li><b>CLOSED</b> — calls go through; the last {@code window} outcomes are kept. Once at least
 *       {@code minCalls} are recorded and the failure share reaches {@code failureRate}, it opens.</li>
 *   <li><b>OPEN</b> — calls fail immediately without touching the dependency, for {@code openNanos}.</li>
 *   <li><b>HALF_OPEN</b> — up to {@code probes} trial calls go through. If all succeed it closes;
 *       one failure opens it again.</li>
 * </ul>
 */
final class CircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    record Transition(long atNanos, State to) {
    }

    private final int window;
    private final int minCalls;
    private final double failureRate;
    private final long openNanos;
    private final int probes;
    private final LongSupplier clock;

    private final boolean[] failed;
    private int next;
    private int recorded;
    private int failures;
    private State state = State.CLOSED;
    private long openedAt;
    private int probesInFlight;
    private int probeSuccesses;
    private final List<Transition> transitions = new ArrayList<>();

    CircuitBreaker(int window, int minCalls, double failureRate, long openNanos, int probes, LongSupplier clock) {
        this.window = window;
        this.minCalls = minCalls;
        this.failureRate = failureRate;
        this.openNanos = openNanos;
        this.probes = probes;
        this.clock = clock;
        this.failed = new boolean[window];
    }

    // forge:snippet circuit-breaker-state
    /** May this call go to the dependency? A {@code true} must be followed by exactly one {@link #onResult}. */
    synchronized boolean tryAcquire() {
        long now = clock.getAsLong();
        if (state == State.OPEN && now - openedAt >= openNanos) {
            moveTo(State.HALF_OPEN, now);                   // cool-down over: let a few trial calls through
            probesInFlight = 0;
            probeSuccesses = 0;
        }
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> false;                             // fail fast: the dependency gets time to recover
            case HALF_OPEN -> {
                if (probesInFlight < probes) {
                    probesInFlight++;
                    yield true;
                }
                yield false;
            }
        };
    }

    synchronized void onResult(boolean ok) {
        long now = clock.getAsLong();
        switch (state) {
            case HALF_OPEN -> {
                probesInFlight--;
                if (!ok) {
                    open(now);                              // still broken: back to OPEN
                } else if (++probeSuccesses >= probes) {
                    moveTo(State.CLOSED, now);              // recovered: forget the old failures
                    java.util.Arrays.fill(failed, false);
                    next = recorded = failures = 0;
                }
            }
            case CLOSED -> {
                if (recorded == window && failed[next]) {
                    failures--;                             // the oldest outcome leaves the window
                }
                failed[next] = !ok;
                failures += ok ? 0 : 1;
                next = (next + 1) % window;
                recorded = Math.min(window, recorded + 1);
                if (recorded >= minCalls && failures >= failureRate * recorded) {
                    open(now);
                }
            }
            case OPEN -> {
                // a call admitted before the breaker opened finished late; it changes nothing
            }
        }
    }
    // forge:end

    private void open(long now) {
        openedAt = now;
        moveTo(State.OPEN, now);
    }

    private void moveTo(State to, long now) {
        if (state != to) {
            state = to;
            transitions.add(new Transition(now, to));
        }
    }

    synchronized State state() {
        return state;
    }

    synchronized List<Transition> transitions() {
        return List.copyOf(transitions);
    }
}
