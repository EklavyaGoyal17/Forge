package dev.forge.lab.scenarios.jvm;

import dev.forge.lab.jvm.JfrCapture;
import dev.forge.lab.jvm.JfrMarker;
import dev.forge.lab.jvm.JfrSummariser;
import dev.forge.lab.jvm.JfrSummary;
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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

/**
 * Allocation-heavy vs allocation-light implementations of the same work, with exact per-thread
 * allocation counts and JFR evidence of where the allocation happened and what the GC did about it.
 *
 * <p>Invariant: every implementation returns exactly the reference result.
 */
public final class AllocationScenario implements Scenario {

    public static final String ID = "jvm.allocation";
    private static final String SRC = AllocationScenario.class.getName() + "#";

    // ---- aggregate: sum of the squares of the even values -------------------------------------

    // forge:snippet boxed-stream
    static long boxedStream(int[] data) {
        return Arrays.stream(data).boxed()           // an Integer per element (outside the -128..127 cache)
                .filter(v -> v % 2 == 0)
                .map(v -> (long) v * v)                // a Long per even element
                .reduce(0L, Long::sum);                // and a Long per partial sum
    }
    // forge:end

    // forge:snippet primitive-stream
    static long primitiveStream(int[] data) {
        return Arrays.stream(data)                   // IntStream: no boxing, a few pipeline objects
                .filter(v -> v % 2 == 0)
                .mapToLong(v -> (long) v * v)
                .sum();
    }
    // forge:end

    // forge:snippet primitive-loop
    static long primitiveLoop(int[] data) {
        long sum = 0;
        for (int v : data) {                         // no objects at all
            if (v % 2 == 0) {
                sum += (long) v * v;
            }
        }
        return sum;
    }
    // forge:end

    // ---- render: every value followed by ';' --------------------------------------------------

    // forge:snippet concat-in-loop
    static String concatInLoop(int[] data) {
        String s = "";
        for (int v : data) {
            s += v + ";";                            // a new String holding everything so far: O(n²) bytes
        }
        return s;
    }
    // forge:end

    // forge:snippet string-builder
    static String stringBuilder(int[] data) {
        StringBuilder sb = new StringBuilder();      // default capacity 16; grows by copying (≈ doubling)
        for (int v : data) {
            sb.append(v).append(';');
        }
        return sb.toString();                        // one more copy into the final String
    }
    // forge:end

    // forge:snippet presized-builder
    static String presizedBuilder(int[] data) {
        StringBuilder sb = new StringBuilder(data.length * 8);   // values < 10^7: at most 7 digits + ';'
        for (int v : data) {
            sb.append(v).append(';');
        }
        return sb.toString();
    }
    // forge:end

    /** Independent formulations used only to check results. */
    static long referenceAggregate(int[] data) {
        long sum = 0;
        for (int i = 0; i < data.length; i++) {
            if ((data[i] & 1) == 0) {
                sum = Math.addExact(sum, Math.multiplyExact((long) data[i], data[i]));
            }
        }
        return sum;
    }

    static String referenceRender(int[] data) {
        return Arrays.stream(data).mapToObj(v -> v + ";").collect(Collectors.joining());
    }

    static ToLongFunction<int[]> aggregate(String variant) {
        return switch (variant) {
            case "boxed-stream" -> AllocationScenario::boxedStream;
            case "primitive-stream" -> AllocationScenario::primitiveStream;
            case "primitive-loop" -> AllocationScenario::primitiveLoop;
            default -> throw new IllegalArgumentException("not an aggregate variant: " + variant);
        };
    }

    static Function<int[], String> render(String variant) {
        return switch (variant) {
            case "concat-in-loop" -> AllocationScenario::concatInLoop;
            case "string-builder" -> AllocationScenario::stringBuilder;
            case "presized-builder" -> AllocationScenario::presizedBuilder;
            default -> throw new IllegalArgumentException("not a render variant: " + variant);
        };
    }

    /** One timed trial. Results are primitive (aggregate) so the harness itself allocates nothing per op. */
    record Measured(long nanos, long allocatedBytes, boolean held) {
    }

    static Measured measure(String variant, boolean aggregate, int[] data, int ops, Object reference) {
        if (aggregate) {
            ToLongFunction<int[]> f = aggregate(variant);
            long expected = (Long) reference;
            long[] results = new long[ops];
            long a0 = JvmLab.threadAllocatedBytes();
            long t0 = System.nanoTime();
            for (int i = 0; i < ops; i++) {
                results[i] = f.applyAsLong(data);
            }
            long nanos = System.nanoTime() - t0;
            long allocated = JvmLab.threadAllocatedBytes() - a0;
            return new Measured(nanos, allocated, Arrays.stream(results).allMatch(r -> r == expected));
        }
        Function<int[], String> f = render(variant);
        String[] results = new String[ops];
        long a0 = JvmLab.threadAllocatedBytes();
        long t0 = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            results[i] = f.apply(data);
        }
        long nanos = System.nanoTime() - t0;
        long allocated = JvmLab.threadAllocatedBytes() - a0;
        return new Measured(nanos, allocated, Arrays.stream(results).allMatch(reference::equals));
    }

    private static final ScenarioDescriptor DESCRIPTOR = new ScenarioDescriptor(
            ID, 1, Lab.JVM,
            "Allocation: heavy vs light implementations",
            "The same result computed with and without temporary objects — measured with HotSpot's exact per-thread allocation counter, JFR allocation samples and GC pauses.",
            "How much garbage does a convenient implementation create, where is it created, and what does it cost in time and GC?",
            "Every implementation returns exactly the reference result.",
            List.of(
                    ParameterSpec.choice("mode", "Work", "aggregate", List.of("aggregate", "render"),
                            "aggregate: sum of squares of the even values. render: every value as text followed by ';'."),
                    ParameterSpec.integer("elements", "Values", 1_000_000, 10_000, 5_000_000, "values",
                            "Input size for the aggregate work.").onlyFor("aggregate"),
                    ParameterSpec.integer("fields", "Values", 4_000, 100, 10_000, "values",
                            "Input size for the render work (concatenation in a loop is quadratic: keep this modest).").onlyFor("render"),
                    ParameterSpec.integer("opsPerTrial", "Operations per trial", 10, 1, 200, "ops",
                            "How many times the work is repeated inside one timed trial.")),
            List.of(
                    new VariantSpec("boxed-stream", "Boxed stream", "Stream<Integer> → Long reduction.", true, SRC + "boxed-stream").onlyFor("aggregate"),
                    new VariantSpec("primitive-stream", "IntStream", "Primitive stream: no boxing.", true, SRC + "primitive-stream").onlyFor("aggregate"),
                    new VariantSpec("primitive-loop", "Primitive loop", "A for loop over int[] with a long accumulator.", true, SRC + "primitive-loop").onlyFor("aggregate"),
                    new VariantSpec("concat-in-loop", "String += in a loop", "Each iteration builds a new String of everything so far.", true, SRC + "concat-in-loop").onlyFor("render"),
                    new VariantSpec("string-builder", "StringBuilder", "One builder that grows as needed.", true, SRC + "string-builder").onlyFor("render"),
                    new VariantSpec("presized-builder", "Presized StringBuilder", "A builder created with enough capacity up front.", true, SRC + "presized-builder").onlyFor("render")),
            List.of(
                    new MetricSpec("msPerOp", "Time per operation", "ms", Direction.LOWER_IS_BETTER,
                            "Wall-clock time of one operation, averaged over the trial."),
                    new MetricSpec("allocatedMbPerOp", "Allocated per operation", "MB", Direction.LOWER_IS_BETTER,
                            "Bytes allocated by the measuring thread per operation (HotSpot's exact per-thread counter), in 10⁶ bytes."),
                    new MetricSpec("gcCount", "GC pauses", "", Direction.LOWER_IS_BETTER,
                            "Garbage collections JFR recorded during the trial."),
                    new MetricSpec("gcPauseMs", "GC pause time", "ms", Direction.LOWER_IS_BETTER,
                            "Sum of stop-the-world pause time JFR recorded during the trial.")),
            "msPerOp",
            new ScenarioDescriptor.TrialPolicy(5, 20, 1, 5),
            180,
            """
            Allocating in Java is cheap — a pointer bump in the thread's TLAB — but not free: every allocated byte has \
            to be zeroed, it fills the young generation, and a full young generation means a GC pause. A boxed stream \
            creates an Integer for each value outside the small-value cache and a Long for every intermediate sum; \
            the JIT's escape analysis can remove some of these, but only when the whole pipeline is inlined. String \
            concatenation inside a loop is worse in a different way: each iteration copies everything built so far into a \
            new String, so the bytes allocated grow with the square of the input. The exact allocation counter shows how \
            much each implementation allocated; JFR's allocation samples show which classes and call sites the bytes \
            came from; the GC events show what that garbage cost in pauses.""",
            List.of(
                    "Allocated bytes come from HotSpot's per-thread counter (ThreadMXBean.getCurrentThreadAllocatedBytes): exact for the measuring thread, including allocations the JIT did not eliminate. Escape analysis that scalar-replaces objects makes them disappear from this count — that is the point, not an error.",
                    "JFR allocation samples are statistical. Each sample's weight is the bytes the thread allocated since its previous sample; when the sampler's rate limit (300/s) is reached during a burst, the remaining bytes are credited to the next sample, which may be in different code. Each thread's first sample is discarded because its weight reaches back to before the recording. Use the exact counter for totals and the samples for where.",
                    "GC pauses depend on the heap limit of the run and on the collector the JVM chose (recorded in the environment); with a smaller heap the same garbage causes more, shorter collections.",
                    "The JFR recording covers the measured trials only (it starts after the warm-up trials); the recording itself allocates a little.",
                    "Results of each operation are kept until the end of the trial for the correctness check, so up to opsPerTrial results are live at once (render mode: opsPerTrial strings).",
                    "Implementations are called through a functional interface from one harness method; the JIT may inline them into it, which is what makes escape analysis possible."));

    @Override
    public ScenarioDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ScenarioResult run(RunSpec spec, RunContext ctx) throws Exception {
        VariantSpec variant = DESCRIPTOR.variant(spec.variant()).orElseThrow();
        String mode = spec.stringParam("mode");
        if (!variant.supportsMode(mode)) {
            throw new IllegalArgumentException("variant " + variant.id() + " does not support mode " + mode);
        }
        int n = mode.equals("aggregate") ? spec.intParam("elements") : spec.intParam("fields");
        int ops = spec.intParam("opsPerTrial");
        int[] data = JvmLab.data(spec.seed(), n, 1_000_000);
        boolean aggregate = mode.equals("aggregate");
        Object reference = aggregate ? referenceAggregate(data) : referenceRender(data);

        for (int w = 0; w < spec.warmupTrials(); w++) {
            ctx.checkCancelled();
            measure(variant.id(), aggregate, data, ops, reference);
            ctx.progress(w + 1, spec.warmupTrials() + spec.trials(), "warm-up");
        }

        ScenarioResult.Builder result = ScenarioResult.builder();
        long[] allocated = new long[spec.trials()];
        long[] nanos = new long[spec.trials()];
        boolean[] held = new boolean[spec.trials()];
        JfrSummary jfr;
        try (JfrCapture capture = JfrCapture.start(ctx.workDir(), "allocation", JfrCapture.Settings.defaults())) {
            for (int t = 0; t < spec.trials(); t++) {
                ctx.checkCancelled();
                JfrMarker.mark("trial-" + t + " start");
                Measured r = measure(variant.id(), aggregate, data, ops, reference);
                JfrMarker.mark("trial-" + t + " end");
                nanos[t] = r.nanos();
                allocated[t] = r.allocatedBytes();
                held[t] = r.held();
                ctx.progress(spec.warmupTrials() + t + 1, spec.warmupTrials() + spec.trials(), "measured");
            }
            jfr = JvmLab.stopAndSummarise(capture, ctx,
                    JfrSummariser.Options.forPackage(JvmLab.PACKAGE).withMaxPauses(5_000));
        }

        boolean pausesComplete = !jfr.recording().truncatedTables().contains("gc.pauses");
        for (int t = 0; t < spec.trials(); t++) {
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("msPerOp", nanos[t] / 1e6 / ops);
            m.put("allocatedMbPerOp", allocated[t] / 1e6 / ops);
            if (pausesComplete) {
                double from = marker(jfr, "trial-" + t + " start");
                double to = marker(jfr, "trial-" + t + " end");
                List<JfrSummary.GcPause> inTrial = jfr.gc().pauses().stream()
                        .filter(p -> p.atMs() >= from && p.atMs() <= to).toList();
                m.put("gcCount", (double) inTrial.size());
                m.put("gcPauseMs", inTrial.stream().mapToDouble(JfrSummary.GcPause::sumOfPausesMs).sum());
            }
            result.trial(new Trial(t, m, held[t], held[t] ? null : "an operation returned a different result than the reference"));
        }
        result.invariant(InvariantSummary.of(DESCRIPTOR.invariant(), result.trials()));

        long exactTotal = Arrays.stream(allocated).sum();
        double perOpMb = exactTotal / 1e6 / ((double) ops * spec.trials());
        result.observation(String.format("Exact allocation by the measuring thread: %.3f MB per operation (%,d bytes over %d operations).",
                perOpMb, exactTotal, ops * spec.trials()));
        if (jfr.allocation().samples() > 0) {
            result.observation(String.format("JFR allocation samples (all threads, statistical): %,d samples weighing %.1f MB vs %.1f MB counted exactly on the measuring thread.",
                    jfr.allocation().samples(), jfr.allocation().sampledBytes() / 1e6, exactTotal / 1e6));
            JfrSummary.AllocationClassRow top = jfr.allocation().byClass().getFirst();
            result.observation(String.format("Heaviest sampled class: %s (%.0f%% of sampled weight).", top.objectClass(), top.share() * 100));
        } else {
            result.observation("JFR took no allocation samples during the measured trials.");
        }
        result.observation(String.format("JFR recorded %d garbage collections with %.1f ms of pauses in total (longest %.2f ms).",
                jfr.gc().collections(), jfr.gc().totalPauseMs(), jfr.gc().maxPauseMs()));
        int violations = result.build().invariant().violations();
        result.observation("Result equal to the reference in " + (spec.trials() - violations) + " of " + spec.trials() + " trials.");

        Map<String, Object> allocation = new LinkedHashMap<>();
        allocation.put("mode", mode);
        allocation.put("size", n);
        allocation.put("opsPerTrial", ops);
        allocation.put("exactAllocatedBytes", exactTotal);
        allocation.put("reference", reference instanceof String s
                ? Map.of("kind", "string", "length", s.length(), "hashCode", s.hashCode())
                : Map.of("kind", "long", "value", reference));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", "allocation");
        details.put("jitMode", JvmLab.jitMode(variant));
        details.put("allocation", allocation);
        details.put("jfr", jfr);
        result.detail("jvm", details);
        return result.build();
    }

    private static double marker(JfrSummary jfr, String label) {
        return jfr.markers().stream().filter(m -> m.label().equals(label)).findFirst()
                .orElseThrow(() -> new IllegalStateException("JFR marker missing: " + label)).atMs();
    }
}
