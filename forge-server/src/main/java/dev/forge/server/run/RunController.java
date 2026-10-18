package dev.forge.server.run;

import dev.forge.lab.stats.Summary;
import dev.forge.server.analysis.ComparisonService;
import dev.forge.server.analysis.RunMetrics;
import dev.forge.server.web.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class RunController {

    private final RunRepository runs;
    private final RunService service;
    private final ComparisonService comparisons;

    public RunController(RunRepository runs, RunService service, ComparisonService comparisons) {
        this.runs = runs;
        this.service = service;
        this.comparisons = comparisons;
    }

    /** A run plus server-computed descriptive statistics for each metric. */
    public record RunView(RunRecord run, Map<String, Summary> summaries, List<RunRepository.ArtifactMeta> artifacts) {
    }

    @GetMapping("/runs")
    public List<RunRecord> recent(@RequestParam(defaultValue = "20") int limit) {
        return runs.recent(Math.max(1, Math.min(limit, 200)));
    }

    @GetMapping("/runs/{id}")
    public RunView get(@PathVariable UUID id) {
        RunRecord run = runs.find(id).orElseThrow(() -> ApiException.notFound("run"));
        return new RunView(run, RunMetrics.summaries(run), runs.artifacts(id));
    }

    @GetMapping("/runs/{id}/events")
    public List<RunRepository.Event> events(@PathVariable UUID id, @RequestParam(defaultValue = "0") long after) {
        runs.find(id).orElseThrow(() -> ApiException.notFound("run"));
        return runs.events(id, after);
    }

    @PostMapping("/runs/{id}/cancel")
    public RunRecord cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }

    /** Raw artifacts are always served as plain text so that nothing in them is ever interpreted by a browser. */
    @GetMapping("/runs/{id}/artifacts/{name}")
    public ResponseEntity<String> artifact(@PathVariable UUID id, @PathVariable String name) {
        RunRepository.Artifact a = runs.artifact(id, name).orElseThrow(() -> ApiException.notFound("artifact"));
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(a.content());
    }

    @GetMapping("/compare")
    public ComparisonService.ComparisonView compare(@RequestParam UUID baseline, @RequestParam UUID candidate) {
        return comparisons.compare(baseline, candidate);
    }
}
