package dev.forge.lab.concurrency;

import dev.forge.lab.concurrency.ControlledExecution.Decision;
import dev.forge.lab.concurrency.ControlledExecution.Execution;
import dev.forge.lab.concurrency.ControlledExecution.NamedTask;
import dev.forge.lab.concurrency.ControlledExecution.Outcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Systematically enumerates every interleaving of a small concurrent program at yield-point
 * granularity using depth-first search over scheduling decisions (stateless model checking, in the
 * style of VeriSoft/CHESS, without partial-order reduction).
 *
 * <p>Each schedule is executed from scratch on fresh state and fresh threads. After an execution,
 * the search backtracks to the deepest decision that still has an untried enabled thread. When the
 * schedule budget is exhausted before the tree is, the report says so ({@code exhaustive = false})
 * and makes no claim about the unexplored part.
 */
public final class InterleavingExplorer {

    private InterleavingExplorer() {
    }

    /** Result of checking the invariant on the final state of one execution. */
    public record Check(boolean held, String outcomeLabel) {
    }

    public record Report(
            int schedulesExplored,
            boolean exhaustive,
            int maxSchedules,
            int violations,
            int deadlocks,
            int stepLimitHits,
            int errors,
            Map<String, Integer> outcomeCounts,
            Execution firstViolation,
            String firstViolationOutcome,
            Execution firstPass,
            String firstError,
            long elapsedNanos) {
    }

    /**
     * @param freshState  creates fresh shared state for each execution
     * @param tasks       builds the worker tasks over that state
     * @param check       evaluates the invariant on the state after a completed execution
     */
    public static <S> Report explore(Supplier<S> freshState,
                                     Function<S, List<NamedTask>> tasks,
                                     Function<S, Check> check,
                                     int maxSchedules,
                                     int maxStepsPerSchedule,
                                     Runnable cancellationCheck) {
        return search(freshState, tasks, check, maxSchedules, maxStepsPerSchedule, cancellationCheck, null);
    }

    /**
     * Random-walk sampling: each schedule picks uniformly among enabled threads at every decision,
     * from a stream seeded with {@code seed + scheduleIndex}. Never exhaustive, but reaches deep,
     * early-diverging schedules (deadlocks, livelocks) that DFS only reaches after exhausting
     * later decisions. Reproducible for a given seed.
     */
    public static <S> Report sample(Supplier<S> freshState,
                                    Function<S, List<NamedTask>> tasks,
                                    Function<S, Check> check,
                                    int schedules,
                                    int maxStepsPerSchedule,
                                    Runnable cancellationCheck,
                                    long seed) {
        return search(freshState, tasks, check, schedules, maxStepsPerSchedule, cancellationCheck, seed);
    }

    private static <S> Report search(Supplier<S> freshState,
                                     Function<S, List<NamedTask>> tasks,
                                     Function<S, Check> check,
                                     int maxSchedules,
                                     int maxStepsPerSchedule,
                                     Runnable cancellationCheck,
                                     Long randomSeed) {
        long start = System.nanoTime();
        List<Decision> path = new ArrayList<>();
        int explored = 0;
        int violations = 0;
        int deadlocks = 0;
        int stepLimit = 0;
        int errors = 0;
        Map<String, Integer> outcomes = new TreeMap<>();
        Execution firstViolation = null;
        String firstViolationOutcome = null;
        Execution firstPass = null;
        String firstError = null;
        boolean exhaustive = false;

        while (explored < maxSchedules) {
            cancellationCheck.run();
            S state = freshState.get();
            ReplayChooser chooser = randomSeed == null
                    ? new ReplayChooser(List.copyOf(path), null)
                    : new ReplayChooser(List.of(), new SplittableRandom(randomSeed + explored));
            Execution exec = ControlledExecution.run(tasks.apply(state), chooser, maxStepsPerSchedule);
            explored++;

            String label;
            boolean violated;
            switch (exec.outcome()) {
                case COMPLETED -> {
                    Check c = check.apply(state);
                    label = c.outcomeLabel();
                    violated = !c.held();
                }
                case DEADLOCK -> {
                    deadlocks++;
                    label = "deadlock";
                    violated = true;
                }
                case STEP_LIMIT -> {
                    stepLimit++;
                    label = "exceeded step bound (possible livelock)";
                    violated = true;
                }
                default -> {
                    errors++;
                    label = "error";
                    violated = true;
                    if (firstError == null) {
                        firstError = exec.error();
                    }
                }
            }
            if (chooser.divergence != null) {
                errors++;
                if (firstError == null) {
                    firstError = chooser.divergence;
                }
            }
            outcomes.merge(label, 1, Integer::sum);
            if (violated) {
                violations++;
                if (firstViolation == null) {
                    firstViolation = exec;
                    firstViolationOutcome = label;
                }
            } else if (firstPass == null) {
                firstPass = exec;
            }

            if (randomSeed != null) {
                continue;
            }
            // Backtrack: the executed decisions become the new path; then advance the deepest
            // decision that still has an untried alternative.
            path = new ArrayList<>(exec.decisions());
            while (!path.isEmpty()) {
                Decision last = path.getLast();
                if (last.chosen() + 1 < last.enabled().size()) {
                    path.set(path.size() - 1, new Decision(last.enabled(), last.chosen() + 1));
                    break;
                }
                path.removeLast();
            }
            if (path.isEmpty()) {
                exhaustive = true;
                break;
            }
        }
        return new Report(explored, exhaustive, maxSchedules, violations, deadlocks, stepLimit, errors, outcomes,
                firstViolation, firstViolationOutcome, firstPass, firstError, System.nanoTime() - start);
    }

    /**
     * Replays a decision prefix, then picks the first enabled thread (DFS) or a uniformly random
     * one (sampling).
     */
    private static final class ReplayChooser implements ControlledExecution.Chooser {
        private final List<Decision> prefix;
        private final SplittableRandom random;
        private int depth;
        String divergence;

        ReplayChooser(List<Decision> prefix, SplittableRandom random) {
            this.prefix = prefix;
            this.random = random;
        }

        @Override
        public int choose(List<Integer> enabled) {
            int d = depth++;
            if (d < prefix.size()) {
                Decision expected = prefix.get(d);
                if (!expected.enabled().equals(enabled)) {
                    // The program is not deterministic given the schedule (e.g. it reads a clock or
                    // a random source). Exploration results would be unsound; report it.
                    if (divergence == null) {
                        divergence = "non-deterministic replay at step " + d + ": expected enabled "
                                + expected.enabled() + " but saw " + enabled;
                    }
                    return 0;
                }
                return expected.chosen();
            }
            return random == null ? 0 : random.nextInt(enabled.size());
        }
    }

    /** Convenience for scenarios: is this an outcome that represents a completed run? */
    public static boolean completed(Execution e) {
        return e.outcome() == Outcome.COMPLETED;
    }
}
