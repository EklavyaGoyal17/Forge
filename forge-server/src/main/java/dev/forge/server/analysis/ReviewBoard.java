package dev.forge.server.analysis;

import dev.forge.lab.stats.Comparison;
import dev.forge.lab.stats.Summary;
import dev.forge.server.experiment.Experiment;
import dev.forge.server.run.RunRecord;
import dev.forge.server.run.RunStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The Engineering Review Board: an explicit, deterministic rubric evaluated against recorded
 * evidence, plus open questions the engineer answers in writing.
 *
 * <p>No item is scored by judgement. Every check states the evidence it used, and the score is
 * simply passed / applicable. Written answers are stored but never graded.
 */
@Service
public class ReviewBoard {

    private final ComparisonService comparisons;

    public ReviewBoard(ComparisonService comparisons) {
        this.comparisons = comparisons;
    }

    public enum Status { PASS, WARN, FAIL, NOT_APPLICABLE }

    public record Check(String id, String criterion, Status status, String evidence) {
    }

    public record Question(String id, String text, String hint) {
    }

    public record Review(List<Check> checks, int passed, int applicable, List<Question> questions,
                         String baselineRunId, String candidateRunId, String rubricNote) {
    }

    public static final List<Question> QUESTIONS = List.of(
            new Question("hypothesis", "What did you hypothesize, and was it written before you saw results?", "State the predicted direction and rough size of the effect."),
            new Question("measured", "What exactly did you measure, and is it the quantity your hypothesis is about?", "Throughput vs latency, mean vs median, per-operation vs per-trial."),
            new Question("changed", "What changed between baseline and candidate — and what else might have changed with it?", "Variant, parameters, environment, machine load, power source."),
            new Question("noise", "Could noise explain the difference?", "Look at the confidence interval, the CV, and whether the result replicated in a second run."),
            new Question("bottleneck", "What bottleneck does the evidence suggest, and what evidence would contradict it?", "Distinguish observation from hypothesis."),
            new Question("alternatives", "What other explanations remain plausible?", "JIT effects, GC, CPU frequency, cache size, contention, measurement overhead."),
            new Question("scale", "What would happen under higher concurrency or larger data?", "Which resource saturates first? Is there a crossover point?"),
            new Question("correctness", "Did the change compromise correctness?", "Invariant checks, edge cases, memory visibility."),
            new Question("production", "Would the improvement survive production-like conditions?", "Different hardware, mixed workloads, tail latency, longer runs."),
            new Question("complexity", "Is the additional complexity justified by the measured benefit?", "Who maintains it? What does it cost to get wrong?"));

    public Review review(Experiment exp, List<RunRecord> runs) {
        List<Check> checks = new ArrayList<>();
        List<RunRecord> ok = runs.stream().filter(r -> r.status() == RunStatus.SUCCEEDED).toList();
        Optional<RunRecord> baseline = latest(ok, "BASELINE");
        Optional<RunRecord> candidate = latest(ok, "CANDIDATE");
        Optional<RunRecord> firstRun = runs.stream().min(Comparator.comparing(RunRecord::queuedAt));

        // 1. hypothesis
        if (exp.hypothesis() == null || exp.hypothesis().isBlank()) {
            checks.add(new Check("hypothesis-recorded", "A hypothesis is recorded", Status.FAIL, "The hypothesis field is empty."));
        } else if (firstRun.isPresent() && exp.hypothesisUpdatedAt().isAfter(firstRun.get().queuedAt())) {
            checks.add(new Check("hypothesis-recorded", "The hypothesis was fixed before the first run", Status.WARN,
                    "The hypothesis was last edited at " + exp.hypothesisUpdatedAt() + ", after the first run was queued at "
                            + firstRun.get().queuedAt() + ". Was it revised to fit the data?"));
        } else {
            checks.add(new Check("hypothesis-recorded", "The hypothesis was fixed before the first run", Status.PASS,
                    "Hypothesis recorded" + firstRun.map(r -> " before the first run (" + r.queuedAt() + ")").orElse("; no runs yet") + "."));
        }

        // 2-3. baseline and candidate exist
        checks.add(baseline.map(r -> new Check("baseline", "A successful baseline run exists", Status.PASS, "Run #" + r.seq() + " (" + r.variant() + ")."))
                .orElse(new Check("baseline", "A successful baseline run exists", Status.FAIL, "No SUCCEEDED run has role BASELINE.")));
        checks.add(candidate.map(r -> new Check("candidate", "A successful candidate run exists", Status.PASS, "Run #" + r.seq() + " (" + r.variant() + ")."))
                .orElse(new Check("candidate", "A successful candidate run exists", Status.FAIL, "No SUCCEEDED run has role CANDIDATE.")));

        if (baseline.isPresent() && candidate.isPresent()) {
            RunRecord b = baseline.get();
            RunRecord c = candidate.get();
            ComparisonService.ComparisonView cmp = comparisons.compare(b, c);
            Optional<ComparisonService.MetricComparison> primary = cmp.metrics().stream().filter(ComparisonService.MetricComparison::primary).findFirst();

            // 4. sample size
            int nb = b.trials();
            int nc = c.trials();
            Status sizeStatus = nb >= Comparison.MIN_RECOMMENDED_N && nc >= Comparison.MIN_RECOMMENDED_N ? Status.PASS : Status.WARN;
            checks.add(new Check("sample-size", "Each side has at least " + Comparison.MIN_RECOMMENDED_N + " measured trials", sizeStatus,
                    "Baseline " + nb + " trials, candidate " + nc + " trials."));

            // 5. only the variant differs
            checks.add(cmp.parameterDifferences().isEmpty()
                    ? new Check("isolated-change", "Only the variant differs between baseline and candidate", Status.PASS,
                    "Parameters identical; variants " + b.variant() + " → " + c.variant() + ".")
                    : new Check("isolated-change", "Only the variant differs between baseline and candidate", Status.FAIL,
                    "Also differs: " + cmp.parameterDifferences().stream().map(d -> d.label() + " (" + d.baseline() + " → " + d.candidate() + ")").toList() + "."));

            // 6. comparable environment
            checks.add(cmp.environmentDifferences().isEmpty()
                    ? new Check("environment", "Both runs used a comparable environment", Status.PASS, "No differences in recorded JVM, OS, CPU or launch limits.")
                    : new Check("environment", "Both runs used a comparable environment", Status.FAIL,
                    "Differs: " + cmp.environmentDifferences().stream().map(ComparisonService.Difference::label).toList() + "."));

            // 7. effect distinguishable from resampling noise
            if (primary.isPresent()) {
                Comparison.Result r = primary.get().result();
                Status s = switch (r.verdict()) {
                    case IMPROVEMENT, REGRESSION -> Status.PASS;
                    case NO_DIFFERENCE_DETECTED -> Status.WARN;
                    default -> Status.NOT_APPLICABLE;
                };
                checks.add(new Check("effect", "The primary-metric difference is distinguishable from noise", s,
                        primary.get().label() + ": " + r.verdict() + ". " + r.interpretation()));

                // 8. variability
                Summary sb = r.baseline();
                Summary sc = r.candidate();
                boolean lowNoise = (Double.isNaN(sb.cv()) || sb.cv() <= 0.10) && (Double.isNaN(sc.cv()) || sc.cv() <= 0.10);
                checks.add(new Check("variability", "Trial-to-trial variability (CV) is at most 10%", lowNoise ? Status.PASS : Status.WARN,
                        String.format("CV baseline %.1f%%, candidate %.1f%%.", pct(sb.cv()), pct(sc.cv()))));
            } else {
                checks.add(new Check("effect", "The primary-metric difference is distinguishable from noise", Status.NOT_APPLICABLE,
                        "The runs do not share the scenario's primary metric."));
            }

            // 9a. a broken baseline is not a performance target
            Boolean baselineHeld = cmp.baselineInvariantHeld();
            if (baselineHeld != null) {
                checks.add(new Check("valid-baseline", "Performance is compared against a correct baseline",
                        baselineHeld ? Status.PASS : Status.WARN,
                        baselineHeld ? "Baseline invariant: " + cmp.baselineInvariant() + "."
                                : "The baseline violates the invariant (" + cmp.baselineInvariant() + "). Its speed is the speed of incorrect code; "
                                + "compare correct implementations with each other to judge the cost of correctness."));
            }

            // 9b. correctness
            Boolean held = cmp.candidateInvariantHeld();
            checks.add(new Check("correctness", "The candidate satisfied the scenario invariant",
                    held == null ? Status.NOT_APPLICABLE : held ? Status.PASS : Status.FAIL,
                    "Candidate invariant: " + cmp.candidateInvariant() + "."));
        }

        // 10. replication
        long baselineRuns = ok.stream().filter(r -> r.role().equals("BASELINE")).count();
        long candidateRuns = ok.stream().filter(r -> r.role().equals("CANDIDATE")).count();
        checks.add(new Check("replication", "The result was replicated (at least two successful runs per role)",
                baselineRuns >= 2 && candidateRuns >= 2 ? Status.PASS : Status.WARN,
                baselineRuns + " baseline and " + candidateRuns + " candidate runs. A second JVM run guards against one-off JIT or OS effects."));

        // 11. notebook
        boolean written = !blank(exp.observations()) && !blank(exp.alternativeExplanations()) && !blank(exp.decision());
        checks.add(new Check("notebook", "Observations, alternative explanations and a decision are written down",
                written ? Status.PASS : Status.WARN,
                "observations: " + filled(exp.observations()) + ", alternatives: " + filled(exp.alternativeExplanations())
                        + ", decision: " + filled(exp.decision()) + "."));

        int applicable = (int) checks.stream().filter(c -> c.status() != Status.NOT_APPLICABLE).count();
        int passed = (int) checks.stream().filter(c -> c.status() == Status.PASS).count();
        return new Review(checks, passed, applicable, QUESTIONS,
                baseline.map(r -> r.id().toString()).orElse(null),
                candidate.map(r -> r.id().toString()).orElse(null),
                "Score = PASS checks / applicable checks. WARN and FAIL both count as not passed. Uses the latest successful BASELINE and CANDIDATE runs. Written answers are recorded, never graded.");
    }

    private static Optional<RunRecord> latest(List<RunRecord> runs, String role) {
        return runs.stream().filter(r -> r.role().equals(role)).max(Comparator.comparingInt(RunRecord::seq));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String filled(String s) {
        return blank(s) ? "missing" : "present";
    }

    private static double pct(double cv) {
        return Double.isNaN(cv) ? 0 : cv * 100;
    }
}
