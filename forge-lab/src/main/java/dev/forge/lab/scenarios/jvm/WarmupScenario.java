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

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JIT warm-up from cold: the same computation timed on every invocation, starting with the first,
 * in four execution modes (tiered, C1 only, C2 only, interpreter only).
 *
 * <p>Each trial loads a fresh copy of {@link WarmupKernel} in a new class loader, so every trial
 * starts cold. JFR records every compilation of the kernel; JFR markers around each trial let the
 * compilation finish times be placed on the invocation axis ("C2 code available from invocation
 * 412").
 *
 * <p>Invariant: compilation does not change the result — every trial's checksum equals the
 * reference computed after the trials.
 */
public final class WarmupScenario implements Scenario {

    public static final String ID = "jvm.warmup";
    private static final String KERNEL = WarmupKernel.class.getName();
    private static final String SRC = KERNEL + "#kernel";

    private static final ScenarioDescriptor DESCRIPTOR = new ScenarioDescriptor(
            ID, 1, Lab.JVM,
            "JIT warm-up: from interpreter to C2",
            "Times every invocation of the same method from the first call onwards, and places each JIT compilation recorded by JFR on that timeline.",
            "How long does a method run slowly before the JIT has compiled it, and what does each compiler tier buy?",
            "JIT compilation does not change results: every trial's checksum equals the reference.",
            List.of(
                    ParameterSpec.integer("invocations", "Invocations per trial", 3_000, 200, 10_000, "calls",
                            "How many times the kernel is called (and timed) in each trial."),
                    ParameterSpec.integer("workSize", "Work per invocation", 10_000, 100, 100_000, "elements",
                            "Array elements the kernel's loop processes per call. Larger work makes each timing less sensitive to timer resolution.")),
            List.of(
                    new VariantSpec("tiered", "Tiered (default)",
                            "The JVM's default: interpreter, then C1 with profiling, then C2.", true, SRC),
                    new VariantSpec("c1-only", "C1 only",
                            "-XX:TieredStopAtLevel=1: fast C1 compilation, no profiling, no C2.", true, SRC)
                            .withJvmFlags("-XX:TieredStopAtLevel=1"),
                    new VariantSpec("c2-only", "C2 only",
                            "-XX:-TieredCompilation: interpreter profiles until C2 compiles; no C1 tier.", true, SRC)
                            .withJvmFlags("-XX:-TieredCompilation"),
                    new VariantSpec("interpreter", "Interpreter only",
                            "-Xint: bytecode is never compiled.", true, SRC)
                            .withJvmFlags("-Xint")),
            List.of(
                    new MetricSpec("totalMs", "Total time", "ms", Direction.LOWER_IS_BETTER,
                            "Sum of all invocation times in the trial: warm-up cost plus steady state."),
                    new MetricSpec("firstInvocationUs", "First invocation", "µs", Direction.LOWER_IS_BETTER,
                            "Time of the very first call (cold, interpreted unless compiled concurrently)."),
                    new MetricSpec("steadyStateUs", "Steady state", "µs", Direction.LOWER_IS_BETTER,
                            "Median time of the last 20% of invocations."),
                    new MetricSpec("warmupCostMs", "Warm-up cost", "ms", Direction.LOWER_IS_BETTER,
                            "Time spent above the steady-state speed: Σ max(0, invocation − steady state)."),
                    new MetricSpec("invocationsToSteady", "Invocations to steady state", "calls", Direction.LOWER_IS_BETTER,
                            "First invocation at which the rolling median of 25 invocations is within 1.5× the steady state."),
                    new MetricSpec("compilations", "Compilations", "", Direction.NEUTRAL,
                            "Compilations of the kernel class recorded by JFR in this trial (including OSR)."),
                    new MetricSpec("c2AtInvocation", "C2 code from invocation", "", Direction.NEUTRAL,
                            "First invocation that started after the first tier-4 (C2) compilation of work() finished.")),
            "totalMs",
            new ScenarioDescriptor.TrialPolicy(5, 10, 0, 0),
            180,
            """
            HotSpot starts every method in the bytecode interpreter, counting invocations and loop back-edges. In the \
            default tiered mode a hot method is first compiled by C1 with profiling (tier 3), which records branch and \
            type profiles, and later recompiled by C2 (tier 4) using those profiles. A long-running loop does not have \
            to wait for its method to be called again: on-stack replacement (OSR) swaps in compiled code in the middle \
            of the loop. Each trial here loads a fresh copy of the kernel class, so its methods have no compiled code \
            and no profile — the same position as the first call after start-up. JFR records every compilation; its \
            finish time is mapped onto the invocation axis, so you can see which tier was running when. C1 only \
            compiles quickly but never reaches C2's code quality; C2 only skips C1 and stays in the slower, profiling \
            interpreter until C2 compiles; -Xint never compiles at all.""",
            List.of(
                    "Warm-up here is per method, not per JVM: shared JDK code (System.nanoTime, the class loader, JFR itself) is already warm from earlier trials, and the first trial also pays for loading and linking that code.",
                    "Compilation runs on background compiler threads. How quickly code is installed depends on how many cores the runner sees and on what else the compiler queue holds; thresholds also vary between JDK versions.",
                    "The timing loop (measure) itself stays interpreted at these invocation counts, so it never inlines work(); a real caller that is compiled could inline it and behave differently.",
                    "Invocation times include anything else that stopped the thread: GC pauses, safepoints for installing code, OS scheduling. Single outliers are expected; compare medians.",
                    "The JIT-mode variants change real JVM flags (-XX:TieredStopAtLevel=1, -XX:-TieredCompilation, -Xint) for the whole runner JVM, including JFR's own code and the result summariser.",
                    "JFR is recording during the trials (compilation events, 10 ms execution samples, allocation samples); its overhead is small but not zero."));

    @Override
    public ScenarioDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ScenarioResult run(RunSpec spec, RunContext ctx) throws Exception {
        VariantSpec variant = DESCRIPTOR.variant(spec.variant()).orElseThrow();
        JvmLab.requireJvmFlags(variant);
        int invocations = spec.intParam("invocations");
        int[] data = JvmLab.data(spec.seed(), spec.intParam("workSize"), Integer.MAX_VALUE);
        byte[] kernelBytes = kernelBytes();
        MethodType measureType = MethodType.methodType(long.class, int[].class, long[].class, long[].class);

        List<long[]> starts = new ArrayList<>();
        List<long[]> durations = new ArrayList<>();
        long[] checksums = new long[spec.trials()];
        JfrSummary jfr;
        try (JfrCapture capture = JfrCapture.start(ctx.workDir(), "warmup", JfrCapture.Settings.defaults())) {
            for (int t = 0; t < spec.trials(); t++) {
                ctx.checkCancelled();
                // a new loader per trial: a class with no compiled code and no profile
                ClassLoader loader = new KernelLoader(loaderName(t), kernelBytes);
                MethodHandle measure = MethodHandles.publicLookup()
                        .findStatic(Class.forName(KERNEL, true, loader), "measure", measureType);
                long[] s = new long[invocations];
                long[] d = new long[invocations];
                JfrMarker.mark(loaderName(t) + " start");
                checksums[t] = invoke(measure, data, s, d);
                JfrMarker.mark(loaderName(t) + " end");
                starts.add(s);
                durations.add(d);
                ctx.progress(t + 1, spec.trials(), "measured");
            }
            jfr = JvmLab.stopAndSummarise(capture, ctx, JfrSummariser.Options.forPackage(JvmLab.PACKAGE));
        }

        // reference computed after the trials, so it cannot occupy the compiler queue during them
        long reference = WarmupKernel.measure(data, new long[invocations], new long[invocations]);

        ScenarioResult.Builder result = ScenarioResult.builder();
        List<Map<String, Object>> series = new ArrayList<>();
        for (int t = 0; t < spec.trials(); t++) {
            long[] d = durations.get(t);
            String loader = loaderName(t);
            List<Map<String, Object>> compiled = placeCompilations(jfr, loader, starts.get(t));
            Map<String, Double> m = metrics(d);
            m.put("compilations", (double) compiled.size());
            compiled.stream()
                    .filter(c -> (int) c.get("level") == 4 && "work".equals(c.get("method")) && (int) c.get("atInvocation") >= 0)
                    .mapToInt(c -> (int) c.get("atInvocation")).min()
                    .ifPresent(i -> m.put("c2AtInvocation", (double) i));
            boolean held = checksums[t] == reference;
            result.trial(new Trial(t, m, held, held ? null : "checksum " + checksums[t] + " ≠ reference " + reference));

            Map<String, Object> s = new LinkedHashMap<>();
            s.put("trial", t);
            s.put("invocationNanos", d);
            s.put("compilations", compiled);
            s.put("deoptimizations", jfr.deoptimizations().stream()
                    .filter(x -> x.classLoader().equals(loader)).toList());
            series.add(s);
        }
        result.invariant(InvariantSummary.of(DESCRIPTOR.invariant(), result.trials()));
        observe(result, variant, jfr);

        Map<String, Object> warmup = new LinkedHashMap<>();
        warmup.put("invocations", invocations);
        warmup.put("workSize", data.length);
        warmup.put("trials", series);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", "warmup");
        details.put("jitMode", JvmLab.jitMode(variant));
        details.put("warmup", warmup);
        details.put("jfr", jfr);
        result.detail("jvm", details);
        return result.build();
    }

    private static long invoke(MethodHandle measure, int[] data, long[] starts, long[] durations) throws Exception {
        try {
            return (long) measure.invokeExact(data, starts, durations);
        } catch (Exception | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static String loaderName(int trial) {
        return "warmup-trial-" + (trial + 1);
    }

    /** Compilations of this trial's kernel copy, with the invocation from which the code was available. */
    static List<Map<String, Object>> placeCompilations(JfrSummary jfr, String loader, long[] startNanos) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JfrSummary.Compilation c : jfr.compilations()) {
            if (!loader.equals(c.classLoader())) {
                continue;
            }
            long readyAt = JfrSummariser.toNanoTime(c.endAtMs(), jfr.markers());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("method", c.method().substring(c.method().lastIndexOf('.') + 1));
            m.put("level", c.level());
            m.put("osr", c.osr());
            m.put("succeeded", c.succeeded());
            m.put("atInvocation", firstStartingAtOrAfter(startNanos, readyAt));
            m.put("compileMs", c.durationMs());
            m.put("codeSize", c.codeSize());
            out.add(m);
        }
        return out;
    }

    /** Index of the first invocation that started at or after {@code nanos}; -1 if none did. */
    static int firstStartingAtOrAfter(long[] startNanos, long nanos) {
        int i = Arrays.binarySearch(startNanos, nanos);
        if (i < 0) {
            i = -i - 1;
        } else {
            while (i > 0 && startNanos[i - 1] == nanos) {
                i--;
            }
        }
        return i >= startNanos.length ? -1 : i;
    }

    static Map<String, Double> metrics(long[] d) {
        int n = d.length;
        double steady = JvmLab.median(d, n - Math.max(1, n / 5), n);
        long total = 0;
        double excess = 0;
        for (long x : d) {
            total += x;
            excess += Math.max(0, x - steady);
        }
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("totalMs", total / 1e6);
        m.put("firstInvocationUs", d[0] / 1e3);
        m.put("steadyStateUs", steady / 1e3);
        m.put("warmupCostMs", excess / 1e6);
        m.put("invocationsToSteady", (double) invocationsToSteady(d, steady));
        return m;
    }

    /**
     * First invocation at which a rolling median of 25 invocations reaches 1.5× the steady state.
     * (Requiring it to stay there made the metric jump to the end of the series whenever a late
     * stretch was slowed by something unrelated, such as OS scheduling.)
     */
    static int invocationsToSteady(long[] d, double steady) {
        int w = Math.min(25, d.length);
        for (int j = 0; j + w <= d.length; j++) {
            if (JvmLab.median(d, j, j + w) <= 1.5 * steady) {
                return j;
            }
        }
        return d.length;
    }

    private static void observe(ScenarioResult.Builder result, VariantSpec variant, JfrSummary jfr) {
        List<Trial> trials = result.trials();
        double first = medianOf(trials, "firstInvocationUs");
        double steady = medianOf(trials, "steadyStateUs");
        result.observation(String.format("Median over %d trials: first invocation %.1f µs, steady state %.2f µs (%.0f× faster once warm).",
                trials.size(), first, steady, first / steady));
        result.observation(String.format("Median invocations to steady state: %.0f; median warm-up cost %.2f ms of %.2f ms total.",
                medianOf(trials, "invocationsToSteady"), medianOf(trials, "warmupCostMs"), medianOf(trials, "totalMs")));
        double c2 = medianOf(trials, "c2AtInvocation");
        if (!Double.isNaN(c2)) {
            result.observation(String.format("C2 code for work() was available from invocation %.0f (median over trials that reached C2).", c2));
        }
        long kernelCompilations = jfr.compilations().stream().filter(c -> c.classLoader().startsWith("warmup-trial-")).count();
        if (kernelCompilations == 0) {
            result.observation("JFR recorded no compilation of the kernel" + (variant.jvmFlags().contains("-Xint")
                    ? " (expected with -Xint: every invocation was interpreted)." : "."));
        }
        result.observation("Checksum equal to the reference in " + (trials.size() - result.build().invariant().violations())
                + " of " + trials.size() + " trials.");
    }

    private static double medianOf(List<Trial> trials, String metric) {
        double[] v = trials.stream().map(t -> t.metrics().get(metric)).filter(x -> x != null)
                .mapToDouble(Double::doubleValue).sorted().toArray();
        if (v.length == 0) {
            return Double.NaN;
        }
        return v.length % 2 == 1 ? v[v.length / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
    }

    private static byte[] kernelBytes() throws IOException {
        try (InputStream in = WarmupScenario.class.getResourceAsStream("WarmupKernel.class")) {
            if (in == null) {
                throw new IllegalStateException("WarmupKernel.class not found on the runner classpath");
            }
            return in.readAllBytes();
        }
    }

    /** Defines its own copy of the kernel class; everything else comes from the parent. */
    private static final class KernelLoader extends ClassLoader {
        private final byte[] kernel;

        KernelLoader(String name, byte[] kernel) {
            super(name, WarmupScenario.class.getClassLoader());
            this.kernel = kernel;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!KERNEL.equals(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    c = defineClass(name, kernel, 0, kernel.length);
                }
                return c;
            }
        }
    }
}
