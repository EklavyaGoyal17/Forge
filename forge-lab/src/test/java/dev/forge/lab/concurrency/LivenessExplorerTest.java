package dev.forge.lab.concurrency;

import dev.forge.lab.concurrency.ControlledExecution.NamedTask;
import dev.forge.lab.concurrency.LivenessExplorer.Report;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Small programs whose state graphs can be drawn by hand. */
class LivenessExplorerTest {

    static final class Cell {
        volatile int x;
        volatile boolean held;

        String snapshot() {
            return "x=" + x + " held=" + held;
        }
    }

    private static Report explore(java.util.function.Function<Cell, List<NamedTask>> tasks) {
        return LivenessExplorer.explore(Cell::new, tasks, Cell::snapshot, c -> null, 1_000, () -> { });
    }

    @Test
    void loopWithProgressHasTwoStatesAndNoLivelock() {
        // write x=1, progress, write x=0, repeat: states (x=0, next "write x = 1") and (x=1, next "write x = 0")
        Report r = explore(c -> List.of(new NamedTask("T", p -> {
            while (true) {
                p.write("x", 1);
                c.x = 1;
                p.progress("work");
                p.write("x", 0);
                c.x = 0;
            }
        })));
        assertThat(r.error()).isNull();
        assertThat(r.complete()).isTrue();
        assertThat(r.states()).isEqualTo(2);
        assertThat(r.edges()).isEqualTo(2);
        assertThat(r.livelock()).isNull();
        assertThat(r.starvation()).isEmpty();
    }

    @Test
    void spinningWithoutProgressIsALivelockWithAOneStepCycle() {
        Report r = explore(c -> List.of(new NamedTask("T", p -> {
            while (true) {
                p.read("x");
                p.value(c.x);
            }
        })));
        assertThat(r.complete()).isTrue();
        assertThat(r.states()).isEqualTo(1);
        assertThat(r.livelock()).isNotNull();
        assertThat(r.livelock().cycle()).hasSize(1);
        assertThat(r.starvation()).containsOnlyKeys("T");
    }

    @Test
    void aCycleInWhichAnEnabledThreadNeverRunsIsUnfairAndNotALivelock() {
        // A spins until B has written x = 1. Spinning at x = 0 forever needs a scheduler that never runs B.
        Report r = explore(c -> List.of(
                new NamedTask("A", p -> {
                    while (true) {
                        p.read("x");
                        int v = c.x;
                        p.value(v);
                        if (v == 1) {
                            p.progress("saw x = 1");
                        }
                    }
                }),
                new NamedTask("B", p -> {
                    p.write("x", 1);
                    c.x = 1;
                })));
        assertThat(r.error()).isNull();
        assertThat(r.complete()).isTrue();
        assertThat(r.states()).isEqualTo(2);
        assertThat(r.livelock()).isNull();
        assertThat(r.starvation()).isEmpty();
    }

    @Test
    void aNonFairLockLetsEitherThreadBeStarvedButIsNotALivelock() {
        // lock m; progress; unlock m — the lock model hands m to whoever is scheduled (barging), and a
        // waiting thread is blocked, not enabled, while the other holds it: a fair starvation cycle.
        java.util.function.Function<Cell, NamedTask> worker = c -> null;
        Report r = explore(c -> {
            Object m = new Object();
            java.util.function.Function<String, NamedTask> t = name -> new NamedTask(name, p -> {
                while (true) {
                    p.acquire("m");
                    synchronized (m) {
                        p.progress("critical section");
                        p.release("m");
                    }
                }
            });
            return List.of(t.apply("A"), t.apply("B"));
        });
        assertThat(r.error()).isNull();
        assertThat(r.complete()).isTrue();
        assertThat(r.deadlocks()).isZero();
        assertThat(r.livelock()).isNull();
        assertThat(r.starvation()).containsOnlyKeys("A", "B");
    }

    @Test
    void aProgramThatIsNotAFunctionOfItsScheduleIsReportedNotJudged() {
        AtomicInteger executions = new AtomicInteger();
        Report r = LivenessExplorer.explore(
                () -> {
                    Cell c = new Cell();
                    c.x = executions.incrementAndGet();   // differs on every execution
                    return c;
                },
                // two first moves from the initial state, so a second execution must replay to it
                c -> List.of(
                        new NamedTask("A", p -> {
                            p.write("held", 1);
                            c.held = true;
                            while (true) {
                                p.read("x");
                            }
                        }),
                        new NamedTask("B", p -> {
                            p.write("held", 1);
                            c.held = true;
                            while (true) {
                                p.read("x");
                            }
                        })),
                Cell::snapshot, c -> null, 1_000, () -> { });
        assertThat(r.error()).contains("non-deterministic");
        assertThat(r.livelock()).isNull();
    }

    @Test
    void safetyIsCheckedInEveryReachableState() {
        // two threads each set x then clear it: x == 2 is reachable only when both are between set and clear
        Report r = LivenessExplorer.explore(Cell::new, c -> List.of(
                        new NamedTask("A", p -> {
                            while (true) {
                                p.atomic("x", "increment");
                                c.x++;
                                p.progress("in");
                                p.atomic("x", "decrement");
                                c.x--;
                            }
                        }),
                        new NamedTask("B", p -> {
                            while (true) {
                                p.atomic("x", "increment");
                                c.x++;
                                p.progress("in");
                                p.atomic("x", "decrement");
                                c.x--;
                            }
                        })),
                Cell::snapshot, c -> c.x > 1 ? "both inside" : null, 1_000, () -> { });
        assertThat(r.complete()).isTrue();
        assertThat(r.safetyViolations()).isEqualTo(1);   // exactly one state: x = 2, both about to decrement
        assertThat(r.firstSafetyViolation()).isEqualTo("both inside");
        assertThat(r.safetyTrace()).hasSize(2);
    }
}
