package dev.forge.server.analysis;

import dev.forge.lab.scenario.MetricSpec;
import dev.forge.lab.scenario.ScenarioCatalog;
import dev.forge.lab.scenario.ScenarioDescriptor;
import dev.forge.lab.stats.Comparison;
import dev.forge.server.run.RunRecord;
import dev.forge.server.run.RunRepository;
import dev.forge.server.run.RunStatus;
import dev.forge.server.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Compares two runs of the same scenario: statistics for every shared metric, plus the checks that
 * decide whether the comparison is meaningful at all (same parameters? same environment?).
 */
@Service
public class ComparisonService {

    /** Environment fields that, if different, confound a comparison. Paths are JSON pointers. */
    private static final List<String[]> ENV_FIELDS = List.of(
            new String[]{"/runner/javaVersion", "Java version"},
            new String[]{"/runner/vmName", "JVM"},
            new String[]{"/runner/availableProcessors", "Processors visible to the JVM"},
            new String[]{"/runner/maxHeapBytes", "Max heap"},
            new String[]{"/runner/garbageCollectors", "Garbage collectors"},
            new String[]{"/runner/osName", "Operating system"},
            new String[]{"/runner/osArch", "Architecture"},
            new String[]{"/host/cpuModel", "CPU model"},
            new String[]{"/host/logicalProcessors", "Host logical processors"},
            new String[]{"/host/inContainer", "Ran in a container"},
            new String[]{"/launch/activeProcessorCountCap", "ActiveProcessorCount cap"},
            new String[]{"/launch/maxConcurrentRuns", "Concurrent runs allowed"});

    private final RunRepository runs;

    public ComparisonService(RunRepository runs) {
        this.runs = runs;
    }

    public record Difference(String field, String label, String baseline, String candidate) {
    }

    public record MetricComparison(String label, String unit, boolean primary, Comparison.Result result) {
    }

    public record ComparisonView(
            UUID baselineRunId,
            UUID candidateRunId,
            String scenarioId,
            String baselineVariant,
            String candidateVariant,
            String primaryMetric,
            List<MetricComparison> metrics,
            List<Difference> parameterDifferences,
            List<Difference> environmentDifferences,
            List<String> comparabilityWarnings,
            String baselineInvariant,
            String candidateInvariant,
            Boolean baselineInvariantHeld,
            Boolean candidateInvariantHeld) {
    }

    public ComparisonView compare(UUID baselineId, UUID candidateId) {
        RunRecord b = runs.find(baselineId).orElseThrow(() -> ApiException.notFound("baseline run"));
        RunRecord c = runs.find(candidateId).orElseThrow(() -> ApiException.notFound("candidate run"));
        return compare(b, c);
    }

    public ComparisonView compare(RunRecord b, RunRecord c) {
        if (b.status() != RunStatus.SUCCEEDED || c.status() != RunStatus.SUCCEEDED) {
            throw new ApiException(HttpStatus.CONFLICT, "both runs must have SUCCEEDED to be compared");
        }
        if (!b.scenarioId().equals(c.scenarioId())) {
            throw ApiException.badRequest("runs belong to different scenarios (" + b.scenarioId() + " vs " + c.scenarioId() + ")");
        }
        ScenarioDescriptor d = ScenarioCatalog.find(b.scenarioId()).map(s -> s.descriptor()).orElse(null);
        List<String> warnings = new ArrayList<>();
        if (b.id().equals(c.id())) {
            warnings.add("Baseline and candidate are the same run.");
        }
        if (b.scenarioVersion() != c.scenarioVersion()) {
            warnings.add("Different scenario versions (v" + b.scenarioVersion() + " vs v" + c.scenarioVersion()
                    + "): the measured code itself may have changed.");
        }
        if (!Objects.equals(b.forgeVersion(), c.forgeVersion())) {
            warnings.add("Different FORGE versions (" + b.forgeVersion() + " vs " + c.forgeVersion() + ").");
        }
        if (b.warmupTrials() != c.warmupTrials()) {
            warnings.add("Different warm-up counts (" + b.warmupTrials() + " vs " + c.warmupTrials()
                    + "): JIT state at the first measured trial may differ.");
        }
        if (b.heapMb() != c.heapMb()) {
            warnings.add("Different heap limits (" + b.heapMb() + " MB vs " + c.heapMb() + " MB).");
        }

        List<Difference> paramDiffs = diffParams(b.params(), c.params());
        if (!paramDiffs.isEmpty()) {
            warnings.add("Parameters other than the variant differ; any difference is confounded with: "
                    + paramDiffs.stream().map(Difference::field).toList() + ".");
        }
        List<Difference> envDiffs = diffEnvironment(b.environment(), c.environment());
        if (!envDiffs.isEmpty()) {
            warnings.add("The runs were measured in different environments: "
                    + envDiffs.stream().map(Difference::label).toList() + ".");
        }

        Map<String, double[]> sb = RunMetrics.samples(b);
        Map<String, double[]> sc = RunMetrics.samples(c);
        String primary = d == null ? null : d.primaryMetric();
        List<MetricComparison> metrics = new ArrayList<>();
        long seed = b.seed() * 31 + c.seed();
        List<String> names = new ArrayList<>(sb.keySet());
        names.removeIf(n -> !sc.containsKey(n));
        names.sort((x, y) -> Boolean.compare(!x.equals(primary), !y.equals(primary)));
        for (String name : names) {
            MetricSpec spec = d == null ? null : d.metric(name).orElse(null);
            MetricSpec.Direction dir = spec == null ? MetricSpec.Direction.NEUTRAL : spec.direction();
            Comparison.Result r = Comparison.compare(name, dir, sb.get(name), sc.get(name), seed);
            metrics.add(new MetricComparison(spec == null ? name : spec.label(), spec == null ? "" : spec.unit(),
                    name.equals(primary), r));
        }
        if (metrics.isEmpty()) {
            warnings.add("The runs share no numeric metrics (e.g. one ran in stress mode and one in explore mode).");
        }

        return new ComparisonView(b.id(), c.id(), b.scenarioId(), b.variant(), c.variant(), primary, metrics,
                paramDiffs, envDiffs, warnings, RunMetrics.invariantText(b), RunMetrics.invariantText(c),
                RunMetrics.invariantHeld(b), RunMetrics.invariantHeld(c));
    }

    static List<Difference> diffParams(JsonNode a, JsonNode b) {
        List<Difference> out = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>();
        a.propertyNames().forEach(keys::add);
        b.propertyNames().forEach(keys::add);
        for (String k : keys) {
            JsonNode va = a.get(k);
            JsonNode vb = b.get(k);
            if (!Objects.equals(text(va), text(vb))) {
                out.add(new Difference("params." + k, k, text(va), text(vb)));
            }
        }
        return out;
    }

    static List<Difference> diffEnvironment(JsonNode a, JsonNode b) {
        List<Difference> out = new ArrayList<>();
        if (a == null || b == null) {
            return out;
        }
        for (String[] f : ENV_FIELDS) {
            String va = text(a.at(f[0]));
            String vb = text(b.at(f[0]));
            if (!Objects.equals(va, vb)) {
                out.add(new Difference(f[0], f[1], va, vb));
            }
        }
        return out;
    }

    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        return n.isValueNode() ? n.asString() : n.toString();
    }
}
