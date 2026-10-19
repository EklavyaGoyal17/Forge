package dev.forge.server.analysis;

import dev.forge.lab.stats.Comparison;
import dev.forge.server.experiment.Experiment;
import dev.forge.server.run.RunRecord;
import dev.forge.server.run.RunRepository;
import dev.forge.server.run.RunStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Regression detection against an experiment's pinned baseline run.
 *
 * <p>Every other succeeded run of the experiment is compared with the pinned run using the same
 * statistics as a manual comparison. The gate is deliberately conservative: a run is only called a
 * regression or an improvement when the runs are comparable (same parameters, same scenario
 * version, same environment) and the bootstrap interval of the primary metric excludes 1.0. A
 * correctness regression (the baseline held its invariant, the run did not) is always a regression.
 */
@Service
public class RegressionService {

    public enum Gate {
        /** Primary metric worse with an interval excluding 1.0, or the invariant newly violated. */
        REGRESSION,
        IMPROVEMENT,
        /** The interval contains 1.0: no difference detected at this sample size (not equivalence). */
        NO_DIFFERENCE_DETECTED,
        /** Both runs produced identical constant values for the primary metric. */
        IDENTICAL,
        /** The primary metric has no better/worse direction. */
        NOT_DIRECTIONAL,
        /** Parameters, scenario version or environment differ, or no metric is shared: no verdict. */
        NOT_COMPARABLE
    }

    public record Check(
            UUID runId,
            int seq,
            String variant,
            Gate gate,
            List<String> reasons,
            Comparison.Result primary,
            List<String> otherRegressedMetrics) {
    }

    public record Report(UUID baselineRunId, Integer baselineSeq, String baselineVariant, String primaryMetric, List<Check> checks) {
        static Report none() {
            return new Report(null, null, null, null, List.of());
        }
    }

    private final RunRepository runs;
    private final ComparisonService comparisons;

    public RegressionService(RunRepository runs, ComparisonService comparisons) {
        this.runs = runs;
        this.comparisons = comparisons;
    }

    public Report check(Experiment experiment) {
        UUID pinned = experiment.pinnedBaselineRunId();
        if (pinned == null) {
            return Report.none();
        }
        List<RunRecord> all = runs.forExperiment(experiment.id());
        RunRecord baseline = all.stream().filter(r -> r.id().equals(pinned)).findFirst().orElse(null);
        if (baseline == null || baseline.status() != RunStatus.SUCCEEDED) {
            return Report.none();
        }
        List<Check> checks = new ArrayList<>();
        String primaryMetric = null;
        for (RunRecord r : all) {
            if (r.id().equals(pinned) || r.status() != RunStatus.SUCCEEDED || !r.scenarioId().equals(baseline.scenarioId())) {
                continue;
            }
            ComparisonService.ComparisonView view = comparisons.compare(baseline, r);
            primaryMetric = view.primaryMetric();
            checks.add(gate(r.id(), r.seq(), r.variant(), view));
        }
        return new Report(baseline.id(), baseline.seq(), baseline.variant(), primaryMetric, checks);
    }

    /** The gate decision for one comparison. Pure, so the rules are unit-testable. */
    static Check gate(UUID runId, int seq, String variant, ComparisonService.ComparisonView view) {
        List<String> reasons = new ArrayList<>();
        Comparison.Result primary = view.metrics().stream()
                .filter(ComparisonService.MetricComparison::primary)
                .map(ComparisonService.MetricComparison::result)
                .findFirst().orElse(null);
        List<String> otherRegressed = view.metrics().stream()
                .filter(m -> !m.primary() && m.result().verdict() == Comparison.Verdict.REGRESSION)
                .map(ComparisonService.MetricComparison::label)
                .toList();

        if (Boolean.TRUE.equals(view.baselineInvariantHeld()) && Boolean.FALSE.equals(view.candidateInvariantHeld())) {
            reasons.add("Correctness: the pinned baseline held its invariant; this run violated it.");
            return new Check(runId, seq, variant, Gate.REGRESSION, reasons, primary, otherRegressed);
        }

        boolean comparable = true;
        if (!view.parameterDifferences().isEmpty()) {
            comparable = false;
            reasons.add("Parameters differ: " + view.parameterDifferences().stream().map(ComparisonService.Difference::label).toList() + ".");
        }
        if (!view.environmentDifferences().isEmpty()) {
            comparable = false;
            reasons.add("Environment differs: " + view.environmentDifferences().stream().map(ComparisonService.Difference::label).toList() + ".");
        }
        for (String w : view.comparabilityWarnings()) {
            if (w.startsWith("Different scenario versions")) {
                comparable = false;
                reasons.add(w);
            }
        }
        if (primary == null) {
            reasons.add("The runs share no value of the primary metric.");
            return new Check(runId, seq, variant, Gate.NOT_COMPARABLE, reasons, null, otherRegressed);
        }
        if (!comparable) {
            return new Check(runId, seq, variant, Gate.NOT_COMPARABLE, reasons, primary, otherRegressed);
        }
        reasons.addAll(primary.warnings());
        Gate gate = switch (primary.verdict()) {
            case REGRESSION -> Gate.REGRESSION;
            case IMPROVEMENT -> Gate.IMPROVEMENT;
            case NO_DIFFERENCE_DETECTED -> Gate.NO_DIFFERENCE_DETECTED;
            case IDENTICAL -> Gate.IDENTICAL;
            case NOT_DIRECTIONAL -> Gate.NOT_DIRECTIONAL;
        };
        return new Check(runId, seq, variant, gate, reasons, primary, otherRegressed);
    }
}
