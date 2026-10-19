package dev.forge.server.system;

import dev.forge.lab.scenario.ScenarioCatalog;
import dev.forge.server.config.RunnerProperties;
import dev.forge.server.lab.LabDatabaseService;
import dev.forge.server.run.RunRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final HostInfo host;
    private final RunnerProperties props;
    private final RunRepository runs;
    private final LabDatabaseService labDatabase;

    public SystemController(HostInfo host, RunnerProperties props, RunRepository runs, LabDatabaseService labDatabase) {
        this.host = host;
        this.props = props;
        this.runs = runs;
        this.labDatabase = labDatabase;
    }

    public record Limits(int maxConcurrentRuns, int queueCapacity, int defaultHeapMb, int maxHeapMb,
                         int maxTimeoutSeconds, int activeProcessorCount, long stdoutCapBytes) {
    }

    public record SystemView(Map<String, Object> host, Limits limits, int pendingRuns, int queuedRuns, int scenarios,
                             String isolation, Map<String, Object> labDatabase) {
    }

    @GetMapping
    public SystemView get() {
        return new SystemView(host.facts(),
                new Limits(props.maxConcurrentRuns(), props.queueCapacity(), props.defaultHeapMb(), props.maxHeapMb(),
                        props.maxTimeoutSeconds(), props.activeProcessorCount(), props.stdoutCapBytes()),
                runs.countPending(), runs.countQueued(), ScenarioCatalog.descriptors().size(),
                "Each run executes in a separate JVM with a heap cap, an empty environment, a private temp directory and a wall-clock timeout. "
                        + "This limits accidents and resource abuse by built-in scenarios; it is not a security sandbox for untrusted code.",
                labDatabase.status());
    }
}
