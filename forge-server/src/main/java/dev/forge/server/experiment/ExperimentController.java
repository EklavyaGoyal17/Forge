package dev.forge.server.experiment;

import dev.forge.lab.scenario.ScenarioCatalog;
import dev.forge.server.analysis.RegressionService;
import dev.forge.server.analysis.ReportService;
import dev.forge.server.analysis.ReviewBoard;
import dev.forge.server.run.RunRecord;
import dev.forge.server.run.RunRepository;
import dev.forge.server.run.RunService;
import dev.forge.server.run.RunStatus;
import dev.forge.server.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {

    private static final Set<String> STATUSES = Set.of("OPEN", "CONCLUDED", "ARCHIVED");
    private static final int MAX_TEXT = 20_000;

    private final ExperimentRepository experiments;
    private final RunRepository runs;
    private final RunService runService;
    private final ReviewBoard reviewBoard;
    private final ReportService reports;
    private final RegressionService regressions;

    public ExperimentController(ExperimentRepository experiments, RunRepository runs, RunService runService,
                                ReviewBoard reviewBoard, ReportService reports, RegressionService regressions) {
        this.experiments = experiments;
        this.runs = runs;
        this.runService = runService;
        this.reviewBoard = reviewBoard;
        this.reports = reports;
        this.regressions = regressions;
    }

    public record CreateExperiment(
            @NotBlank @Size(max = 200) String title,
            @Size(max = MAX_TEXT) String question,
            @NotBlank(message = "write the hypothesis before running anything") @Size(max = MAX_TEXT) String hypothesis,
            @NotBlank String scenarioId,
            List<@Size(max = 40) String> tags) {
    }

    public record ExperimentDetail(Experiment experiment, List<RunRecord> runs) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Experiment create(@Valid @RequestBody CreateExperiment req) {
        if (ScenarioCatalog.find(req.scenarioId()).isEmpty()) {
            throw ApiException.badRequest("unknown scenario " + req.scenarioId());
        }
        List<String> tags = req.tags() == null ? List.of() : req.tags().stream().map(String::strip).filter(t -> !t.isEmpty()).distinct().limit(10).toList();
        return experiments.insert(UUID.randomUUID(), req.title().strip(), nz(req.question()), req.hypothesis().strip(),
                req.scenarioId(), tags);
    }

    @GetMapping
    public List<ExperimentRepository.Summary> search(@RequestParam(required = false) String q,
                                                     @RequestParam(required = false) String lab,
                                                     @RequestParam(defaultValue = "100") int limit) {
        String labFilter = lab == null || lab.isBlank() ? null : lab.toLowerCase(Locale.ROOT);
        return experiments.search(q, labFilter, Math.max(1, Math.min(limit, 500)));
    }

    @GetMapping("/{id}")
    public ExperimentDetail get(@PathVariable UUID id) {
        Experiment e = experiments.find(id).orElseThrow(() -> ApiException.notFound("experiment"));
        return new ExperimentDetail(e, runs.forExperiment(id));
    }

    /**
     * Partial update of notebook fields. Accepts camelCase keys; unknown keys are rejected so a typo
     * never silently drops a user's notes.
     */
    @PatchMapping("/{id}")
    public Experiment update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        experiments.find(id).orElseThrow(() -> ApiException.notFound("experiment"));
        Map<String, Object> fields = new LinkedHashMap<>();
        for (var e : body.entrySet()) {
            String column = switch (e.getKey()) {
                case "title" -> "title";
                case "question" -> "question";
                case "hypothesis" -> "hypothesis";
                case "status" -> "status";
                case "observations" -> "observations";
                case "alternativeExplanations" -> "alternative_explanations";
                case "decision" -> "decision";
                case "followUps" -> "follow_ups";
                case "reviewAnswers" -> "review_answers";
                case "tags" -> "tags";
                default -> throw ApiException.badRequest("field '" + e.getKey() + "' cannot be updated");
            };
            Object value = e.getValue();
            switch (column) {
                case "review_answers" -> {
                    if (!(value instanceof Map<?, ?> m) || m.values().stream().anyMatch(v -> !(v instanceof String s) || s.length() > MAX_TEXT)) {
                        throw ApiException.badRequest("reviewAnswers must be an object of strings");
                    }
                }
                case "tags" -> {
                    if (!(value instanceof List<?> l) || l.size() > 10 || l.stream().anyMatch(v -> !(v instanceof String s) || s.length() > 40)) {
                        throw ApiException.badRequest("tags must be at most 10 strings of at most 40 characters");
                    }
                }
                default -> {
                    if (!(value instanceof String s) || s.length() > MAX_TEXT) {
                        throw ApiException.badRequest(e.getKey() + " must be a string of at most " + MAX_TEXT + " characters");
                    }
                    if (column.equals("title") && (s.isBlank() || s.length() > 200)) {
                        throw ApiException.badRequest("title must be 1–200 characters");
                    }
                    if (column.equals("status") && !STATUSES.contains(s)) {
                        throw ApiException.badRequest("status must be one of " + STATUSES);
                    }
                }
            }
            fields.put(column, value);
        }
        experiments.update(id, fields);
        return experiments.find(id).orElseThrow();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        experiments.find(id).orElseThrow(() -> ApiException.notFound("experiment"));
        if (runs.forExperiment(id).stream().anyMatch(r -> !r.status().terminal())) {
            throw new ApiException(HttpStatus.CONFLICT, "cancel queued and running runs before deleting the experiment");
        }
        experiments.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunRecord createRun(@PathVariable UUID id, @RequestBody RunService.CreateRun req) {
        return runService.create(id, req);
    }

    public record PinBaseline(UUID runId) {
    }

    /** Pins a succeeded run of this experiment as the baseline that every other run is checked against. */
    @PutMapping("/{id}/pinned-baseline")
    public Experiment pinBaseline(@PathVariable UUID id, @RequestBody PinBaseline body) {
        experiments.find(id).orElseThrow(() -> ApiException.notFound("experiment"));
        if (body == null || body.runId() == null) {
            throw ApiException.badRequest("runId is required");
        }
        RunRecord run = runs.find(body.runId()).filter(r -> r.experimentId().equals(id))
                .orElseThrow(() -> ApiException.badRequest("run " + body.runId() + " does not belong to this experiment"));
        if (run.status() != RunStatus.SUCCEEDED) {
            throw new ApiException(HttpStatus.CONFLICT, "only a SUCCEEDED run can be pinned as the baseline (run #" + run.seq() + " is " + run.status() + ")");
        }
        experiments.pinBaseline(id, run.id());
        return experiments.find(id).orElseThrow();
    }

    @DeleteMapping("/{id}/pinned-baseline")
    public Experiment unpinBaseline(@PathVariable UUID id) {
        experiments.find(id).orElseThrow(() -> ApiException.notFound("experiment"));
        experiments.pinBaseline(id, null);
        return experiments.find(id).orElseThrow();
    }

    /** Every other succeeded run checked against the pinned baseline; empty when nothing is pinned. */
    @GetMapping("/{id}/regressions")
    public RegressionService.Report regressions(@PathVariable UUID id) {
        Experiment e = experiments.find(id).orElseThrow(() -> ApiException.notFound("experiment"));
        return regressions.check(e);
    }

    @GetMapping("/{id}/review")
    public ReviewBoard.Review review(@PathVariable UUID id) {
        Experiment e = experiments.find(id).orElseThrow(() -> ApiException.notFound("experiment"));
        return reviewBoard.review(e, runs.forExperiment(id));
    }

    @GetMapping(value = "/{id}/report.md", produces = "text/markdown")
    public ResponseEntity<String> report(@PathVariable UUID id) {
        Experiment e = experiments.find(id).orElseThrow(() -> ApiException.notFound("experiment"));
        List<RunRecord> list = runs.forExperiment(id);
        String md = reports.markdown(e, list);
        String filename = "forge-" + e.title().replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "").toLowerCase(Locale.ROOT) + ".md";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(filename).build().toString())
                .body(md);
    }

    private static String nz(String s) {
        return s == null ? "" : s.strip();
    }
}
