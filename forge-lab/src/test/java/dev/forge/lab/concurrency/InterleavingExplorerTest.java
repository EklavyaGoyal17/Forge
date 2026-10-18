package dev.forge.lab.concurrency;

import dev.forge.lab.concurrency.ControlledExecution.NamedTask;
import dev.forge.lab.concurrency.ControlledExecution.Outcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

class InterleavingExplorerTest {

    /** Minimal racy counter: read and write are separate yield points. */
    static final class Racy {
        long value;

        void increment(Probe p) {
            p.read("v");
            long c = value;
            p.value(c);
            p.write("v", c + 1);
            value = c + 1;
        }
    }

    static final class Locked {
        final ReentrantLock lock = new ReentrantLock();
        long value;

        void increment(Probe p) {
            p.acquire("L");
            lock.lock();
            try {
                p.read("v");
                long c = value;
                p.write("v", c + 1);
                value = c + 1;
            } finally {
                p.release("L");
                lock.unlock();
            }
        }
    }

    private static final Runnable NO_CANCEL = () -> { };

    @Test
    void twoRacyIncrementsHaveSixSchedulesOfWhichFourLoseAnUpdate() {
        // Orderings of {R1, W1} and {R2, W2} preserving program order: C(4,2) = 6.
        // Only R1 W1 R2 W2 and R2 W2 R1 W1 are serial; the other four lose an update.
        var report = InterleavingExplorer.explore(
                Racy::new,
                s -> List.of(new NamedTask("T1", s::increment), new NamedTask("T2", s::increment)),
                s -> new InterleavingExplorer.Check(s.value == 2, "value = " + s.value),
                1_000, 100, NO_CANCEL);

        assertThat(report.exhaustive()).isTrue();
        assertThat(report.schedulesExplored()).isEqualTo(6);
        assertThat(report.violations()).isEqualTo(4);
        assertThat(report.outcomeCounts()).containsEntry("value = 1", 4).containsEntry("value = 2", 2);
        assertThat(report.firstViolation().steps()).extracting(ControlledExecution.Step::kind)
                .containsExactly("READ", "READ", "WRITE", "WRITE");
    }

    @Test
    void lockedIncrementsNeverViolateAndOnlyOrderCriticalSections() {
        var report = InterleavingExplorer.explore(
                Locked::new,
                s -> List.of(new NamedTask("T1", s::increment), new NamedTask("T2", s::increment)),
                s -> new InterleavingExplorer.Check(s.value == 2, "value = " + s.value),
                1_000, 100, NO_CANCEL);

        assertThat(report.exhaustive()).isTrue();
        assertThat(report.violations()).isZero();
        assertThat(report.errors()).isZero();
        // whoever acquires first runs its whole critical section: exactly two schedules
        assertThat(report.schedulesExplored()).isEqualTo(2);
    }

    @Test
    void oppositeLockOrderIsDetectedAsDeadlockWithWaitForCycle() {
        record Pair(ReentrantLock a, ReentrantLock b) {
        }
        var report = InterleavingExplorer.explore(
                () -> new Pair(new ReentrantLock(), new ReentrantLock()),
                s -> List.of(
                        new NamedTask("T1", p -> nested(p, s.a(), "A", s.b(), "B")),
                        new NamedTask("T2", p -> nested(p, s.b(), "B", s.a(), "A"))),
                s -> new InterleavingExplorer.Check(true, "completed"),
                1_000, 100, NO_CANCEL);

        assertThat(report.exhaustive()).isTrue();
        assertThat(report.deadlocks()).isPositive();
        assertThat(report.firstViolation().outcome()).isEqualTo(Outcome.DEADLOCK);
        assertThat(report.firstViolation().waitFor())
                .containsExactlyInAnyOrder("T1 waits for B held by T2", "T2 waits for A held by T1");
    }

    @Test
    void randomSamplingIsReproducibleForASeed() {
        var r1 = InterleavingExplorer.sample(Racy::new,
                s -> List.of(new NamedTask("T1", s::increment), new NamedTask("T2", s::increment)),
                s -> new InterleavingExplorer.Check(s.value == 2, "value = " + s.value),
                50, 100, NO_CANCEL, 99);
        var r2 = InterleavingExplorer.sample(Racy::new,
                s -> List.of(new NamedTask("T1", s::increment), new NamedTask("T2", s::increment)),
                s -> new InterleavingExplorer.Check(s.value == 2, "value = " + s.value),
                50, 100, NO_CANCEL, 99);
        assertThat(r1.exhaustive()).isFalse();
        assertThat(r1.outcomeCounts()).isEqualTo(r2.outcomeCounts());
        assertThat(r1.violations()).isEqualTo(r2.violations());
    }

    @Test
    void stepBoundStopsNonTerminatingSchedules() {
        var report = InterleavingExplorer.explore(
                () -> new long[1],
                s -> List.of(new NamedTask("spinner", p -> {
                    while (true) {
                        p.read("flag");
                    }
                })),
                s -> new InterleavingExplorer.Check(true, "done"),
                10, 25, NO_CANCEL);
        assertThat(report.stepLimitHits()).isEqualTo(1);
        assertThat(report.exhaustive()).isTrue();
    }

    private static void nested(Probe p, ReentrantLock first, String fn, ReentrantLock second, String sn) {
        p.acquire(fn);
        first.lock();
        try {
            p.acquire(sn);
            second.lock();
            try {
                p.write("x", 1);
            } finally {
                p.release(sn);
                second.unlock();
            }
        } finally {
            p.release(fn);
            first.unlock();
        }
    }
}
