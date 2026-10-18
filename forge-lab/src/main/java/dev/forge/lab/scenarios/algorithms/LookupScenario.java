package dev.forge.lab.scenarios.algorithms;

import dev.forge.lab.scenario.Lab;
import dev.forge.lab.scenario.MetricSpec;
import dev.forge.lab.scenario.MetricSpec.Direction;
import dev.forge.lab.scenario.ParameterSpec;
import dev.forge.lab.scenario.RunContext;
import dev.forge.lab.scenario.RunSpec;
import dev.forge.lab.scenario.Scenario;
import dev.forge.lab.scenario.ScenarioDescriptor;
import dev.forge.lab.scenario.ScenarioResult;
import dev.forge.lab.scenario.ScenarioResult.Trial;
import dev.forge.lab.scenario.VariantSpec;
import org.openjdk.jmh.results.BenchmarkResult;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.format.OutputFormatFactory;
import org.openjdk.jmh.runner.options.TimeValue;
import org.openjdk.jmh.runner.options.VerboseMode;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Algorithm Lab: membership lookup strategies, measured with JMH. */
public final class LookupScenario implements Scenario {

    public static final String ID = "algorithms.lookup";
    private static final String SRC = LookupBenchmark.class.getName() + "#";
    private static final int MAX_LOG_BYTES = 256 * 1024;

    private static final Map<String, String> METHODS = Map.of(
            "linear-array", "linearArray",
            "linear-list", "linearList",
            "hash-set", "hashSet",
            "binary-search", "binarySearch");

    private static final ScenarioDescriptor DESCRIPTOR = new ScenarioDescriptor(
            ID, 1, Lab.ALGORITHMS,
            "Membership lookup: linear vs hash vs binary search",
            "How long does one 'is x in the collection?' query take, by data structure, size and hit ratio? Measured by JMH in forked JVMs.",
            "At what collection size does an O(1) or O(log n) lookup actually beat a linear scan on this machine, and does the hit ratio change the answer?",
            null,
            List.of(
                    ParameterSpec.integer("size", "Collection size", 1_000, 1, 1_000_000, "elements",
                            "Distinct ints in the collection."),
                    ParameterSpec.integer("hitPercent", "Hit ratio", 50, 0, 100, "%",
                            "Share of queries that are present. Misses force a linear scan to read everything."),
                    ParameterSpec.integer("iterationMs", "Iteration time", 500, 100, 5_000, "ms",
                            "Duration of each JMH warm-up and measurement iteration."),
                    ParameterSpec.integer("forks", "Forks", 2, 1, 5, "JVMs",
                            "Separate JVMs JMH starts. Iterations within one fork are correlated; forks are closer to independent.")),
            List.of(
                    new VariantSpec("linear-array", "Linear scan (int[])", "Sequential scan of a primitive array.", null, SRC + "linear-array"),
                    new VariantSpec("linear-list", "ArrayList.contains", "Sequential scan with boxing and equals() calls.", null, SRC + "linear-list"),
                    new VariantSpec("hash-set", "HashSet.contains", "Hash table lookup on boxed Integers.", null, SRC + "hash-set"),
                    new VariantSpec("binary-search", "Arrays.binarySearch", "Binary search over a sorted primitive array.", null, SRC + "binary-search")),
            List.of(
                    new MetricSpec("nsPerLookup", "Time per lookup", "ns", Direction.LOWER_IS_BETTER,
                            "JMH average time of one measurement iteration divided by the 1024 lookups per invocation.")),
            "nsPerLookup",
            new ScenarioDescriptor.TrialPolicy(5, 30, 3, 20),
            600,
            """
            Asymptotic complexity describes how cost grows, not what it is. A linear scan over a small primitive array \
            touches a few contiguous cache lines and is easy for the CPU to predict, so it can beat a hash lookup that \
            must compute a hash, follow a pointer to a boxed Integer and call equals(). As the collection grows, the \
            O(n) term dominates and the crossover appears. Where that crossover lies depends on element type (boxed \
            vs primitive), memory layout, hit ratio, and CPU cache sizes — which is exactly why it has to be measured.

            Each *trial* in this scenario is one JMH measurement iteration; warm-up trials are JMH warm-up iterations. \
            JMH runs them in freshly forked JVMs and consumes return values to prevent dead-code elimination.""",
            List.of(
                    "All queries are pre-generated and repeat every 1024 lookups, so branch predictors and caches see a smaller working set than a real workload might.",
                    "The ints are dense even numbers; HashSet's hash spreading behaves well for them. Adversarial or clustered keys could change the result.",
                    "Iterations within a fork are autocorrelated; the comparison interval treats them as independent and is therefore optimistic. Use ≥ 2 forks.",
                    "Laptop CPUs change frequency with temperature and power source; compare runs made under the same conditions.",
                    "Results for one size do not extrapolate: rerun at the sizes your system actually uses."));

    @Override
    public ScenarioDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ScenarioResult run(RunSpec spec, RunContext ctx) throws Exception {
        String method = METHODS.get(spec.variant());
        int forks = spec.intParam("forks");
        long iterationMs = spec.longParam("iterationMs");
        Path log = ctx.workDir().resolve("jmh.log");

        Options opt = new OptionsBuilder()
                .include(Pattern.quote(LookupBenchmark.class.getName()) + "\\." + method + "$")
                .param("size", String.valueOf(spec.longParam("size")))
                .param("hitPercent", String.valueOf(spec.longParam("hitPercent")))
                .param("seed", String.valueOf(spec.seed()))
                .warmupIterations(spec.warmupTrials())
                .warmupTime(TimeValue.milliseconds(iterationMs))
                .measurementIterations(spec.trials())
                .measurementTime(TimeValue.milliseconds(iterationMs))
                .forks(forks)
                .shouldFailOnError(true)
                .build();

        ctx.info("JMH: " + method + ", " + forks + " fork(s) × (" + spec.warmupTrials() + " warm-up + "
                + spec.trials() + " measurement) iterations of " + iterationMs + " ms");
        Collection<RunResult> runResults;
        // JMH's own output(file) option leaves the file open; own the stream so it is closed.
        try (PrintStream jmhOut = new PrintStream(Files.newOutputStream(log), true, StandardCharsets.UTF_8)) {
            runResults = new Runner(opt, OutputFormatFactory.createFormatInstance(jmhOut, VerboseMode.NORMAL)).run();
        } finally {
            attachLog(ctx, log);
        }

        ScenarioResult.Builder result = ScenarioResult.builder();
        List<Map<String, Object>> perFork = new ArrayList<>();
        int index = 0;
        for (RunResult rr : runResults) {
            int fork = 0;
            for (BenchmarkResult br : rr.getBenchmarkResults()) {
                List<Double> scores = new ArrayList<>();
                for (IterationResult ir : br.getIterationResults()) {
                    double score = ir.getPrimaryResult().getScore();
                    scores.add(score);
                    result.trial(new Trial(index++, Map.of("nsPerLookup", score), null, "fork " + (fork + 1)));
                }
                perFork.add(Map.of("fork", fork + 1, "scores", scores));
                fork++;
            }
            Result<?> primary = rr.getPrimaryResult();
            Map<String, Object> jmh = new LinkedHashMap<>();
            jmh.put("benchmark", rr.getParams().getBenchmark());
            jmh.put("mode", rr.getParams().getMode().longLabel());
            jmh.put("score", primary.getScore());
            jmh.put("scoreError", primary.getScoreError());
            jmh.put("scoreUnit", primary.getScoreUnit());
            double[] ci = primary.getScoreConfidence();
            jmh.put("confidence99_9", List.of(ci[0], ci[1]));
            jmh.put("forks", perFork);
            jmh.put("jmhVersion", org.openjdk.jmh.util.Version.getPlainVersion());
            result.detail("jmh", jmh);
            result.observation(String.format("JMH score: %.3f ± %.3f %s (99.9%% CI reported by JMH, %d forks × %d iterations).",
                    primary.getScore(), primary.getScoreError(), primary.getScoreUnit(), forks, spec.trials()));
        }
        if (result.trials().isEmpty()) {
            throw new IllegalStateException("JMH produced no results; see the jmh.log artifact");
        }
        return result.build();
    }

    private static void attachLog(RunContext ctx, Path log) {
        try {
            if (Files.exists(log)) {
                byte[] bytes = Files.readAllBytes(log);
                String text = new String(bytes, 0, Math.min(bytes.length, MAX_LOG_BYTES), StandardCharsets.UTF_8);
                if (bytes.length > MAX_LOG_BYTES) {
                    text += "\n… truncated at " + MAX_LOG_BYTES + " bytes";
                }
                ctx.artifact("jmh.log", "text/plain", text);
            }
        } catch (Exception e) {
            ctx.warn("could not read JMH log: " + e.getMessage());
        }
    }
}
