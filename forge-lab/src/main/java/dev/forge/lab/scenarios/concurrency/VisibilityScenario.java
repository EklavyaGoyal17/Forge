package dev.forge.lab.scenarios.concurrency;

import dev.forge.lab.scenario.Lab;
import dev.forge.lab.scenario.MetricSpec;
import dev.forge.lab.scenario.MetricSpec.Direction;
import dev.forge.lab.scenario.ParameterSpec;
import dev.forge.lab.scenario.RunContext;
import dev.forge.lab.scenario.RunSpec;
import dev.forge.lab.scenario.Scenario;
import dev.forge.lab.scenario.ScenarioDescriptor;
import dev.forge.lab.scenario.ScenarioResult;
import dev.forge.lab.scenario.ScenarioResult.InvariantSummary;
import dev.forge.lab.scenario.ScenarioResult.Trial;
import dev.forge.lab.scenario.VariantSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A reader thread spins until a writer thread sets a stop flag. Without a happens-before edge the
 * JIT is allowed to read the flag once and never again.
 *
 * <p>Invariant: the reader observes the stop request within the observation window.
 */
public final class VisibilityScenario implements Scenario {

    public static final String ID = "concurrency.visibility";
    private static final String SRC = VisibilityScenario.class.getName() + "#";

    interface StopFlag {
        void requestStop();

        /** Spins until the stop is observed; returns the number of loop iterations. */
        long spinUntilStopped();
    }

    // forge:snippet plain
    static final class PlainFlag implements StopFlag {
        private boolean stop;                       // no happens-before between writer and reader

        @Override
        public void requestStop() {
            stop = true;
        }

        @Override
        public long spinUntilStopped() {
            long iterations = 0;
            while (!stop) {                         // C2 may hoist this load out of the loop
                iterations++;
            }
            return iterations;
        }
    }
    // forge:end

    // forge:snippet volatile
    static final class VolatileFlag implements StopFlag {
        private volatile boolean stop;              // volatile write happens-before subsequent volatile reads

        @Override
        public void requestStop() {
            stop = true;
        }

        @Override
        public long spinUntilStopped() {
            long iterations = 0;
            while (!stop) {                         // must be re-read on every iteration
                iterations++;
            }
            return iterations;
        }
    }
    // forge:end

    // forge:snippet atomic
    static final class AtomicFlag implements StopFlag {
        private final AtomicBoolean stop = new AtomicBoolean();  // get()/set() have volatile semantics

        @Override
        public void requestStop() {
            stop.set(true);
        }

        @Override
        public long spinUntilStopped() {
            long iterations = 0;
            while (!stop.get()) {
                iterations++;
            }
            return iterations;
        }
    }
    // forge:end

    private static StopFlag flag(String variant) {
        return switch (variant) {
            case "plain" -> new PlainFlag();
            case "volatile" -> new VolatileFlag();
            case "atomic" -> new AtomicFlag();
            default -> throw new IllegalArgumentException("unknown variant " + variant);
        };
    }

    private static final ScenarioDescriptor DESCRIPTOR = new ScenarioDescriptor(
            ID, 1, Lab.CONCURRENCY,
            "Visibility: the stop flag that never stops",
            "A reader spins on a boolean until a writer sets it. With a plain field the reader may never see the write.",
            "Does a write to a plain field by one thread become visible to another thread that is spinning on it?",
            "The reader observes the stop request within the observation window.",
            List.of(
                    ParameterSpec.integer("spinBeforeStopMs", "Spin before stop", 300, 0, 5_000, "ms",
                            "How long the reader spins before the writer sets the flag. Longer gives the JIT time to compile the loop."),
                    ParameterSpec.integer("observeWindowMs", "Observation window", 1_000, 50, 10_000, "ms",
                            "How long to wait for the reader to exit after the write.")),
            List.of(
                    new VariantSpec("plain", "Plain boolean", "No synchronization between writer and reader.", false, SRC + "plain"),
                    new VariantSpec("volatile", "volatile boolean", "Volatile write/read pair establishes happens-before.", true, SRC + "volatile"),
                    new VariantSpec("atomic", "AtomicBoolean", "Same guarantee as volatile through the atomic API.", true, SRC + "atomic")),
            List.of(
                    new MetricSpec("observed", "Stop observed", "0/1", Direction.HIGHER_IS_BETTER, "1 if the reader exited within the window."),
                    new MetricSpec("stopLatencyMicros", "Stop latency", "µs", Direction.LOWER_IS_BETTER, "Time from the write to the reader exiting (only when observed)."),
                    new MetricSpec("spinIterations", "Loop iterations", "", Direction.NEUTRAL, "Iterations the reader completed before exiting (only when observed).")),
            "observed",
            new ScenarioDescriptor.TrialPolicy(5, 8, 0, 0),
            120,
            """
            The Java Memory Model only guarantees that one thread sees another's write if there is a happens-before \
            relationship between them — for example a volatile write followed by a volatile read of the same field, or \
            unlocking and then locking the same monitor. Without it, the JIT may legally treat the plain field as a \
            loop-invariant: it reads it once, keeps it in a register, and spins forever. This is a compiler \
            optimisation, not a CPU cache problem: on x86 the hardware would make the store visible within nanoseconds, \
            but the compiled loop never asks again.""",
            List.of(
                    "Whether the plain variant hangs depends on whether the loop was JIT-compiled before the write: with a very short spin the interpreter may still be running it and will see the write.",
                    "A reader that never stops keeps spinning on a daemon thread until the runner JVM exits, using one core. Later trials in the same run therefore compete with earlier stuck readers; the trial count is capped for this reason.",
                    "Correct behaviour of the plain variant on one JVM/CPU proves nothing: the JMM permits the hang even where it does not happen.",
                    "Stop latency of the correct variants is dominated by OS thread scheduling and timer resolution, not by memory-system latency."));

    @Override
    public ScenarioDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ScenarioResult run(RunSpec spec, RunContext ctx) throws Exception {
        long spinMs = spec.longParam("spinBeforeStopMs");
        long windowMs = spec.longParam("observeWindowMs");
        ScenarioResult.Builder result = ScenarioResult.builder();
        int stuck = 0;
        for (int t = 0; t < spec.trials(); t++) {
            ctx.checkCancelled();
            StopFlag flag = flag(spec.variant());
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch exited = new CountDownLatch(1);
            long[] exitNanos = new long[1];
            long[] iterations = new long[1];
            Thread reader = new Thread(() -> {
                started.countDown();
                long n = flag.spinUntilStopped();
                exitNanos[0] = System.nanoTime();
                iterations[0] = n;
                exited.countDown();               // countDown publishes exitNanos/iterations to the driver
            }, "reader-" + t);
            reader.setDaemon(true);                  // a reader that never stops must not keep the JVM alive
            reader.start();
            started.await();
            Thread.sleep(spinMs);
            long writeNanos = System.nanoTime();
            flag.requestStop();
            boolean observed = exited.await(windowMs, TimeUnit.MILLISECONDS);

            Map<String, Double> m = new LinkedHashMap<>();
            m.put("observed", observed ? 1.0 : 0.0);
            if (observed) {
                m.put("stopLatencyMicros", Math.max(0, exitNanos[0] - writeNanos) / 1e3);
                m.put("spinIterations", (double) iterations[0]);
            } else {
                stuck++;
            }
            result.trial(new Trial(t, m, observed,
                    observed ? null : "reader still spinning " + windowMs + " ms after the write"));
            ctx.progress(t + 1, spec.trials(), "measured");
        }
        InvariantSummary inv = InvariantSummary.of(DESCRIPTOR.invariant(), result.trials());
        result.invariant(inv);
        result.observation("Reader failed to observe the stop request within " + windowMs + " ms in "
                + stuck + " of " + spec.trials() + " trials.");
        if (stuck > 0) {
            result.observation(stuck + " reader thread(s) were still spinning when the run ended.");
        }
        return result.build();
    }
}
