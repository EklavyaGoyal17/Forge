package dev.forge.server.analysis;

import dev.forge.lab.scenario.ScenarioCatalog;
import dev.forge.lab.scenario.ScenarioDescriptor;
import dev.forge.lab.stats.Summary;
import dev.forge.server.config.Json;
import dev.forge.server.experiment.Experiment;
import dev.forge.server.run.RunRecord;
import dev.forge.server.run.RunStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Renders an experiment and its evidence as a self-contained Markdown engineering report. */
@Service
public class ReportService {

    private final ReviewBoard reviewBoard;
    private final ComparisonService comparisons;
    private final RegressionService regressions;
    private final Json json;

    public ReportService(ReviewBoard reviewBoard, ComparisonService comparisons, RegressionService regressions, Json json) {
        this.reviewBoard = reviewBoard;
        this.comparisons = comparisons;
        this.regressions = regressions;
        this.json = json;
    }

    public String markdown(Experiment e, List<RunRecord> runs) {
        Optional<ScenarioDescriptor> d = ScenarioCatalog.find(e.scenarioId()).map(s -> s.descriptor());
        StringBuilder md = new StringBuilder();
        md.append("# ").append(e.title()).append("\n\n");
        md.append("> FORGE engineering report · experiment `").append(e.id()).append("` · status ").append(e.status())
                .append(" · created ").append(e.createdAt()).append("\n\n");

        section(md, "Engineering question", e.question());
        section(md, "Hypothesis", e.hypothesis());
        md.append("## Scenario\n\n");
        md.append("`").append(e.scenarioId()).append("`");
        d.ifPresent(x -> md.append(" — ").append(x.title()).append(" (v").append(x.version()).append(")"));
        md.append("\n\n");
        d.ifPresent(x -> {
            if (x.invariant() != null) {
                md.append("**Invariant:** ").append(x.invariant()).append("\n\n");
            }
        });

        md.append("## Runs\n\n");
        if (runs.isEmpty()) {
            md.append("_No runs recorded._\n\n");
        } else {
            md.append("| # | Role | Variant | Status | Trials (+warm-up) | Seed | Invariant | Duration |\n");
            md.append("|---|---|---|---|---|---|---|---|\n");
            for (RunRecord r : runs) {
                md.append("| ").append(r.seq()).append(" | ").append(r.role()).append(" | `").append(r.variant()).append("` | ")
                        .append(r.status()).append(" | ").append(r.trials()).append(" (+").append(r.warmupTrials()).append(") | ")
                        .append(r.seed()).append(" | ").append(r.status() == RunStatus.SUCCEEDED ? RunMetrics.invariantText(r) : "—")
                        .append(" | ").append(r.durationMillis() == null ? "—" : r.durationMillis() + " ms").append(" |\n");
            }
            md.append("\n");
        }

        for (RunRecord r : runs) {
            if (r.status() != RunStatus.SUCCEEDED) {
                if (r.statusReason() != null) {
                    md.append("### Run ").append(r.seq()).append(" — ").append(r.status()).append("\n\n")
                            .append(r.statusReason()).append("\n\n");
                }
                continue;
            }
            md.append("### Run ").append(r.seq()).append(" — ").append(r.role().toLowerCase()).append(" `").append(r.variant()).append("`");
            if (!r.label().isBlank()) {
                md.append(" (").append(r.label()).append(")");
            }
            md.append("\n\n");
            md.append("Parameters: `").append(json.write(r.params())).append("`\n\n");
            Map<String, Summary> sums = RunMetrics.summaries(r);
            if (!sums.isEmpty()) {
                md.append("| Metric | n | median | mean | stddev | min | max | p95 | CV |\n|---|---|---|---|---|---|---|---|---|\n");
                sums.forEach((name, s) -> md.append("| ").append(name).append(" | ").append(s.n()).append(" | ")
                        .append(num(s.median())).append(" | ").append(num(s.mean())).append(" | ").append(num(s.stddev()))
                        .append(" | ").append(num(s.min())).append(" | ").append(num(s.max())).append(" | ").append(num(s.p95()))
                        .append(" | ").append(Double.isNaN(s.cv()) ? "—" : String.format("%.1f%%", s.cv() * 100)).append(" |\n"));
                md.append("\n");
            }
            JsonNode obs = r.result() == null ? null : r.result().get("observations");
            if (obs != null && obs.isArray() && !obs.isEmpty()) {
                md.append("Recorded observations:\n\n");
                obs.forEach(o -> md.append("- ").append(o.asString()).append("\n"));
                md.append("\n");
            }
            appendEnvironment(md, r.environment());
        }

        ReviewBoard.Review review = reviewBoard.review(e, runs);
        if (review.baselineRunId() != null && review.candidateRunId() != null) {
            RunRecord b = runs.stream().filter(r -> r.id().toString().equals(review.baselineRunId())).findFirst().orElseThrow();
            RunRecord c = runs.stream().filter(r -> r.id().toString().equals(review.candidateRunId())).findFirst().orElseThrow();
            ComparisonService.ComparisonView cmp = comparisons.compare(b, c);
            md.append("## Comparison: run ").append(b.seq()).append(" (baseline) vs run ").append(c.seq()).append(" (candidate)\n\n");
            md.append("| Metric | Baseline median | Candidate median | Ratio | 95% CI | Mann–Whitney p | Verdict |\n|---|---|---|---|---|---|---|\n");
            for (ComparisonService.MetricComparison m : cmp.metrics()) {
                var r = m.result();
                md.append("| ").append(m.primary() ? "**" + m.label() + "**" : m.label()).append(m.unit().isBlank() ? "" : " (" + m.unit() + ")")
                        .append(" | ").append(num(r.baseline().median())).append(" | ").append(num(r.candidate().median()))
                        .append(" | ").append(num(r.ratioOfMedians())).append(" | [").append(num(r.ciLow())).append(", ").append(num(r.ciHigh()))
                        .append("] | ").append(r.mannWhitney() == null ? "—" : String.format("%.4f", r.mannWhitney().pValue()))
                        .append(" | ").append(r.verdict()).append(" |\n");
            }
            md.append("\n");
            cmp.metrics().stream().filter(ComparisonService.MetricComparison::primary).findFirst()
                    .ifPresent(m -> md.append(m.result().interpretation()).append("\n\n"));
            if (!cmp.comparabilityWarnings().isEmpty()) {
                md.append("**Comparability warnings**\n\n");
                cmp.comparabilityWarnings().forEach(w -> md.append("- ").append(w).append("\n"));
                md.append("\n");
            }
        }

        RegressionService.Report gate = regressions.check(e);
        if (gate.baselineRunId() != null) {
            md.append("## Regression gate: pinned baseline run ").append(gate.baselineSeq())
                    .append(" (").append(gate.baselineVariant()).append(")\n\n");
            if (gate.checks().isEmpty()) {
                md.append("_No other succeeded run to check._\n\n");
            } else {
                md.append("| Run | Variant | Gate | Primary ratio | 95% CI | Reasons |\n|---|---|---|---|---|---|\n");
                for (RegressionService.Check c : gate.checks()) {
                    var p = c.primary();
                    md.append("| ").append(c.seq()).append(" | ").append(c.variant()).append(" | **").append(c.gate()).append("** | ")
                            .append(p == null ? "—" : num(p.ratioOfMedians())).append(" | ")
                            .append(p == null ? "—" : "[" + num(p.ciLow()) + ", " + num(p.ciHigh()) + "]").append(" | ")
                            .append(String.join(" ", c.reasons()).replace("|", "\\|")).append(" |\n");
                }
                md.append("\nA run is gated as a regression only when it is comparable with the pinned baseline and the 95% ")
                        .append("bootstrap interval of the primary metric excludes 1.0 on the worse side, or when it violates an ")
                        .append("invariant the baseline held.\n\n");
            }
        }

        section(md, "Observations", e.observations());
        section(md, "Alternative explanations", e.alternativeExplanations());
        section(md, "Decision", e.decision());
        section(md, "Follow-up experiments", e.followUps());

        md.append("## Engineering Review Board\n\n");
        md.append("Score: **").append(review.passed()).append(" / ").append(review.applicable()).append("** applicable checks passed. ")
                .append(review.rubricNote()).append("\n\n");
        md.append("| Check | Status | Evidence |\n|---|---|---|\n");
        for (ReviewBoard.Check c : review.checks()) {
            md.append("| ").append(c.criterion()).append(" | ").append(c.status()).append(" | ")
                    .append(c.evidence().replace("|", "\\|").replace("\n", " ")).append(" |\n");
        }
        md.append("\n");
        JsonNode answers = e.reviewAnswers();
        boolean any = false;
        for (ReviewBoard.Question q : ReviewBoard.QUESTIONS) {
            String a = answers == null || answers.get(q.id()) == null ? "" : answers.get(q.id()).asString();
            if (!a.isBlank()) {
                if (!any) {
                    md.append("### Reviewer questions\n\n");
                    any = true;
                }
                md.append("**").append(q.text()).append("**\n\n").append(a).append("\n\n");
            }
        }

        d.ifPresent(x -> {
            md.append("## Limitations of this scenario\n\n");
            x.caveats().forEach(c -> md.append("- ").append(c).append("\n"));
            md.append("\n");
        });
        md.append("## Reproducing\n\n");
        md.append("Each run above records its scenario version, variant, parameters, seed, trial counts and runner JVM flags. ")
                .append("Re-queue a run with the same values on a comparable machine; compare environments before comparing numbers.\n");
        return md.toString();
    }

    private static void appendEnvironment(StringBuilder md, JsonNode env) {
        if (env == null) {
            return;
        }
        JsonNode r = env.path("runner");
        JsonNode h = env.path("host");
        JsonNode l = env.path("launch");
        md.append("<details><summary>Environment</summary>\n\n");
        md.append("- JVM: ").append(r.path("vmName").asString("?")).append(" ").append(r.path("javaVersion").asString("?"))
                .append(", GC ").append(r.path("garbageCollectors").toString()).append("\n");
        md.append("- Processors visible: ").append(r.path("availableProcessors").asString("?"))
                .append(", max heap ").append(r.path("maxHeapBytes").asLong() / (1024 * 1024)).append(" MB\n");
        md.append("- Host: ").append(h.path("cpuModel").asString("unknown CPU")).append(", ")
                .append(h.path("logicalProcessors").asString("?")).append(" logical processors, ")
                .append(h.path("osName").asString("?")).append("\n");
        md.append("- Runner flags: `").append(l.path("jvmFlags").toString()).append("`\n\n</details>\n\n");
    }

    private static void section(StringBuilder md, String title, String body) {
        md.append("## ").append(title).append("\n\n").append(body == null || body.isBlank() ? "_Not recorded._" : body.strip()).append("\n\n");
    }

    static String num(double v) {
        if (Double.isNaN(v)) {
            return "—";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return String.format("%.0f", v);
        }
        double a = Math.abs(v);
        return a >= 100 ? String.format("%.1f", v) : a >= 1 ? String.format("%.3f", v) : String.format("%.4g", v);
    }
}
