package dev.forge.server.investigator;

import dev.forge.lab.scenario.ScenarioCatalog;
import dev.forge.lab.scenario.ScenarioDescriptor;
import dev.forge.lab.stats.Comparison;
import dev.forge.lab.stats.Summary;
import dev.forge.server.analysis.ComparisonService;
import dev.forge.server.analysis.RegressionService;
import dev.forge.server.analysis.ReviewBoard;
import dev.forge.server.analysis.RunMetrics;
import dev.forge.server.experiment.Experiment;
import dev.forge.server.investigator.Evidence.Item;
import dev.forge.server.run.RunRecord;
import dev.forge.server.run.RunStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds the evidence bundle for an experiment from stored records only: the experiment's question
 * and hypothesis, the user's notes (marked as such), the scenario's caveats, and for every run its
 * configuration, environment, metric summaries, invariant, recorded observations and a compact
 * summary of its lab-specific details; then comparisons, the regression gate and the review board.
 *
 * <p>Numbers are written with at most 4 significant digits, so the answer checker can recognise them.
 */
@Component
public class EvidenceBuilder {

    private static final int MAX_RUNS = 12;

    private final ComparisonService comparisons;
    private final RegressionService regressions;
    private final ReviewBoard reviewBoard;

    public EvidenceBuilder(ComparisonService comparisons, RegressionService regressions, ReviewBoard reviewBoard) {
        this.comparisons = comparisons;
        this.regressions = regressions;
        this.reviewBoard = reviewBoard;
    }

    public Evidence.Bundle build(Experiment e, List<RunRecord> allRuns) {
        List<Item> items = new ArrayList<>();
        Optional<ScenarioDescriptor> d = ScenarioCatalog.find(e.scenarioId()).map(s -> s.descriptor());
        items.add(new Item("Q", "EXPERIMENT", "Question and hypothesis",
                null, "Question: " + blank(e.question()) + "\nHypothesis: " + blank(e.hypothesis())));
        String notes = joinNonBlank(
                labelled("Observations", e.observations()), labelled("Alternative explanations", e.alternativeExplanations()),
                labelled("Decision", e.decision()), labelled("Follow-ups", e.followUps()));
        if (!notes.isBlank()) {
            items.add(new Item("N", "NOTES", "The experimenter's notebook (their writing, not measurements)", null, notes));
        }
        d.ifPresent(desc -> items.add(new Item("S", "SCENARIO", "Scenario " + desc.id() + " v" + desc.version() + ": " + desc.title(), null,
                "Invariant: " + blank(desc.invariant()) + "\nPrimary metric: " + desc.primaryMetric()
                        + "\nCaveats:\n" + desc.caveats().stream().map(c -> "- " + c).collect(Collectors.joining("\n")))));

        List<RunRecord> runs = allRuns.stream().sorted(Comparator.comparingInt(RunRecord::seq).reversed()).limit(MAX_RUNS)
                .sorted(Comparator.comparingInt(RunRecord::seq)).toList();
        if (allRuns.size() > runs.size()) {
            items.add(new Item("RUNS", "RUN", "Runs included", null,
                    "Only the latest " + runs.size() + " of " + allRuns.size() + " runs are included."));
        }
        for (RunRecord r : runs) {
            addRun(items, r, d.orElse(null));
        }
        addComparisons(items, runs);
        if (e.pinnedBaselineRunId() != null) {
            RegressionService.Report report = regressions.check(e);
            StringBuilder sb = new StringBuilder("Pinned baseline: run #" + report.baselineSeq() + " (" + report.baselineVariant() + ")\n");
            for (RegressionService.Check c : report.checks()) {
                sb.append("- run #").append(c.seq()).append(" ").append(c.variant()).append(": ").append(c.gate())
                        .append(c.reasons().isEmpty() ? "" : " — " + String.join("; ", c.reasons())).append('\n');
            }
            items.add(new Item("G", "REGRESSION", "Regression gate", null, sb.toString().trim()));
        }
        ReviewBoard.Review review = reviewBoard.review(e, allRuns);
        items.add(new Item("B", "REVIEW", "Engineering Review Board rubric (" + review.passed() + "/" + review.applicable() + " passed)", null,
                review.checks().stream().map(c -> "- " + c.status() + " " + c.criterion() + ": " + c.evidence()).collect(Collectors.joining("\n"))));
        return new Evidence.Bundle(e.id(), e.scenarioId(), d.map(ScenarioDescriptor::title).orElse(e.scenarioId()), List.copyOf(items));
    }

    private void addRun(List<Item> items, RunRecord r, ScenarioDescriptor d) {
        String id = "R" + r.seq();
        items.add(new Item(id, "RUN", "Run #" + r.seq() + " " + r.variant() + " (" + r.role().toLowerCase() + ")", r.seq(),
                "Status: " + r.status() + (r.statusReason() == null ? "" : " — " + r.statusReason())
                        + "\nScenario version: " + r.scenarioVersion() + "; variant: " + r.variant()
                        + (r.label() == null || r.label().isBlank() ? "" : "; label: " + r.label())
                        + "\nTrials: " + r.trials() + " measured + " + r.warmupTrials() + " warm-up; seed " + r.seed()
                        + "; heap " + r.heapMb() + " MB; timeout " + r.timeoutSeconds() + " s"
                        + "\nParameters: " + r.params()
                        + (r.durationMillis() == null ? "" : "\nWall-clock duration: " + r.durationMillis() + " ms")));
        JsonNode env = r.environment();
        if (env != null && !env.isNull()) {
            JsonNode host = env.path("host");
            JsonNode runner = env.path("runner");
            JsonNode launch = env.path("launch");
            items.add(new Item(id + ".env", "ENVIRONMENT", "Run #" + r.seq() + " environment", r.seq(),
                    "CPU: " + host.path("cpuModel").asString("?") + ", " + host.path("logicalProcessors").asString("?") + " logical processors; "
                            + "memory " + mb(host.path("totalMemoryBytes")) + " MB; OS " + host.path("osName").asString("?") + " " + host.path("osVersion").asString("")
                            + "; in container: " + host.path("inContainer").asString("?")
                            + "\nRunner JVM: " + runner.path("vmName").asString("?") + " " + runner.path("javaVersion").asString("?")
                            + "; JIT " + runner.path("jitCompiler").asString("?")
                            + "\nLaunch flags: " + launch.path("jvmFlags")));
        }
        if (r.status() != RunStatus.SUCCEEDED || r.result() == null) {
            return;
        }
        Map<String, Summary> summaries = RunMetrics.summaries(r);
        for (var m : summaries.entrySet()) {
            String unit = d == null ? "" : d.metric(m.getKey()).map(x -> x.unit()).orElse("");
            String label = d == null ? m.getKey() : d.metric(m.getKey()).map(x -> x.label()).orElse(m.getKey());
            boolean primary = d != null && d.primaryMetric().equals(m.getKey());
            Summary s = m.getValue();
            items.add(new Item(id + ".m." + m.getKey(), "METRIC", "Run #" + r.seq() + " " + label + (primary ? " (primary)" : ""), r.seq(),
                    m.getKey() + " [" + unit + "] over " + s.n() + " trials: median " + n(s.median()) + ", mean " + n(s.mean())
                            + ", stddev " + n(s.stddev()) + ", min " + n(s.min()) + ", max " + n(s.max())
                            + ", CV " + (Double.isFinite(s.cv()) ? n(100 * s.cv()) + "%" : "n/a")));
        }
        JsonNode inv = r.result().path("invariant");
        if (!inv.path("held").isNull() && !inv.path("held").isMissingNode()) {
            items.add(new Item(id + ".inv", "INVARIANT", "Run #" + r.seq() + " invariant", r.seq(),
                    RunMetrics.invariantText(r) + " — " + inv.path("description").asString("")));
        }
        JsonNode obs = r.result().path("observations");
        if (obs.isArray() && !obs.isEmpty()) {
            List<String> lines = new ArrayList<>();
            obs.forEach(o -> lines.add("- " + o.asString()));
            items.add(new Item(id + ".obs", "OBSERVATION", "Run #" + r.seq() + " recorded observations (generated from its data)", r.seq(),
                    String.join("\n", lines)));
        }
        String detail = DetailDigest.of(r.result().path("details"));
        if (!detail.isBlank()) {
            items.add(new Item(id + ".detail", "DETAIL", "Run #" + r.seq() + " lab-specific evidence", r.seq(), detail));
        }
    }

    /** Every candidate against the latest baseline before it; with no roles, consecutive runs. */
    private void addComparisons(List<Item> items, List<RunRecord> runs) {
        List<RunRecord> ok = runs.stream().filter(r -> r.status() == RunStatus.SUCCEEDED && r.result() != null).toList();
        List<RunRecord[]> pairs = new ArrayList<>();
        for (RunRecord c : ok) {
            if (!c.role().equals("CANDIDATE")) {
                continue;
            }
            ok.stream().filter(b -> b.role().equals("BASELINE") && b.seq() < c.seq()).max(Comparator.comparingInt(RunRecord::seq))
                    .ifPresent(b -> pairs.add(new RunRecord[] {b, c}));
        }
        if (pairs.isEmpty()) {
            for (int i = 1; i < ok.size(); i++) {
                pairs.add(new RunRecord[] {ok.get(i - 1), ok.get(i)});
            }
        }
        for (RunRecord[] p : pairs.subList(0, Math.min(pairs.size(), 8))) {
            ComparisonService.ComparisonView v = comparisons.compare(p[0], p[1]);
            StringBuilder sb = new StringBuilder("Baseline run #" + p[0].seq() + " (" + v.baselineVariant() + ") vs candidate run #"
                    + p[1].seq() + " (" + v.candidateVariant() + ")\n");
            for (ComparisonService.MetricComparison mc : v.metrics()) {
                Comparison.Result res = mc.result();
                sb.append("- ").append(res.metric()).append(mc.primary() ? " (primary)" : "").append(": ").append(res.verdict())
                        .append("; ratio of medians ").append(n(res.ratioOfMedians()))
                        .append(" (95% bootstrap CI ").append(n(res.ciLow())).append("–").append(n(res.ciHigh())).append(")")
                        .append(res.mannWhitney() == null ? "" : "; Mann–Whitney p " + n(res.mannWhitney().pValue()))
                        .append('\n');
            }
            v.parameterDifferences().forEach(x -> sb.append("Parameter differs: ").append(x.label()).append(" ").append(x.baseline())
                    .append(" → ").append(x.candidate()).append('\n'));
            v.environmentDifferences().forEach(x -> sb.append("Environment differs: ").append(x.label()).append(" ").append(x.baseline())
                    .append(" → ").append(x.candidate()).append('\n'));
            v.comparabilityWarnings().forEach(w -> sb.append("Warning: ").append(w).append('\n'));
            items.add(new Item("C" + p[0].seq() + "-" + p[1].seq(), "COMPARISON", "Run #" + p[0].seq() + " vs run #" + p[1].seq(), p[1].seq(),
                    sb.toString().trim()));
        }
    }

    /** At most 4 significant digits, plain notation. */
    static String n(double v) {
        if (!Double.isFinite(v)) {
            return Double.isNaN(v) ? "n/a" : (v > 0 ? "∞" : "−∞");
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return new BigDecimal(v).round(new MathContext(4)).stripTrailingZeros().toPlainString();
    }

    private static String mb(JsonNode bytes) {
        return bytes.isNumber() ? Long.toString(bytes.asLong() / (1024 * 1024)) : "?";
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? "(not recorded)" : s.strip();
    }

    private static String labelled(String label, String text) {
        return text == null || text.isBlank() ? "" : label + ": " + text.strip();
    }

    private static String joinNonBlank(String... parts) {
        return java.util.Arrays.stream(parts).filter(p -> !p.isBlank()).collect(Collectors.joining("\n"));
    }
}
