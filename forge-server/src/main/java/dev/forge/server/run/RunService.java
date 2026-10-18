package dev.forge.server.run;

import dev.forge.lab.scenario.ParameterValidator;
import dev.forge.lab.scenario.ParameterValidator.FieldError;
import dev.forge.lab.scenario.Scenario;
import dev.forge.lab.scenario.ScenarioCatalog;
import dev.forge.lab.scenario.ScenarioDescriptor;
import dev.forge.server.config.RunnerProperties;
import dev.forge.server.experiment.Experiment;
import dev.forge.server.experiment.ExperimentRepository;
import dev.forge.server.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Validates, enqueues and cancels runs. Execution itself lives in {@link RunQueue}. */
@Service
public class RunService {

    public static final Set<String> ROLES = Set.of("BASELINE", "CANDIDATE", "EXPLORATORY");

    private final RunRepository runs;
    private final ExperimentRepository experiments;
    private final RunnerProperties props;
    private final String forgeVersion;
    private final SecureRandom seeds = new SecureRandom();
    private final Set<UUID> cancelRequests = ConcurrentHashMap.newKeySet();
    private volatile Runnable wakeUp = () -> { };

    public RunService(RunRepository runs, ExperimentRepository experiments, RunnerProperties props,
                      @Value("${forge.version:dev}") String forgeVersion) {
        this.runs = runs;
        this.experiments = experiments;
        this.props = props;
        this.forgeVersion = forgeVersion;
    }

    public record CreateRun(String variant, Map<String, Object> params, Long seed, Integer trials,
                            Integer warmupTrials, String role, String label, Integer heapMb, Integer timeoutSeconds) {
    }

    void onEnqueue(Runnable wakeUp) {
        this.wakeUp = wakeUp;
    }

    public RunRecord create(UUID experimentId, CreateRun req) {
        Experiment exp = experiments.find(experimentId).orElseThrow(() -> ApiException.notFound("experiment"));
        if ("ARCHIVED".equals(exp.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "experiment is archived");
        }
        Scenario scenario = ScenarioCatalog.find(exp.scenarioId())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "scenario " + exp.scenarioId() + " is no longer available"));
        ScenarioDescriptor d = scenario.descriptor();

        ParameterValidator.Result v = ParameterValidator.validate(d, req.variant(), req.params(), req.trials(), req.warmupTrials());
        List<FieldError> errors = new ArrayList<>(v.errors());
        String role = req.role() == null ? "EXPLORATORY" : req.role().toUpperCase(java.util.Locale.ROOT);
        if (!ROLES.contains(role)) {
            errors.add(new FieldError("role", "must be one of " + ROLES));
        }
        int heap = req.heapMb() == null ? props.defaultHeapMb() : req.heapMb();
        if (heap < 64 || heap > props.maxHeapMb()) {
            errors.add(new FieldError("heapMb", "must be between 64 and " + props.maxHeapMb()));
        }
        int timeout = req.timeoutSeconds() == null ? Math.min(d.defaultTimeoutSeconds(), props.maxTimeoutSeconds()) : req.timeoutSeconds();
        if (timeout < 5 || timeout > props.maxTimeoutSeconds()) {
            errors.add(new FieldError("timeoutSeconds", "must be between 5 and " + props.maxTimeoutSeconds()));
        }
        String label = req.label() == null ? "" : req.label().strip();
        if (label.length() > 120) {
            errors.add(new FieldError("label", "at most 120 characters"));
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors);
        }
        if (runs.countQueued() >= props.queueCapacity()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "run queue is full (" + props.queueCapacity() + " queued); wait for runs to finish");
        }
        long seed = req.seed() != null ? req.seed() : seeds.nextLong(1, Long.MAX_VALUE);
        RunRecord run = runs.insert(new RunRepository.NewRun(UUID.randomUUID(), experimentId, label, role, d.id(),
                d.version(), req.variant(), v.params(), seed, v.trials(), v.warmupTrials(), heap, timeout, forgeVersion));
        runs.addEvent(run.id(), Instant.now(), "INFO", "queued: " + d.id() + " / " + req.variant() + ", " + v.trials()
                + " trials (+" + v.warmupTrials() + " warm-up), seed " + seed);
        wakeUp.run();
        return run;
    }

    public RunRecord cancel(UUID runId) {
        RunRecord run = runs.find(runId).orElseThrow(() -> ApiException.notFound("run"));
        if (run.status().terminal()) {
            throw new ApiException(HttpStatus.CONFLICT, "run already " + run.status());
        }
        if (runs.cancelIfQueued(runId, "cancelled before start")) {
            runs.addEvent(runId, Instant.now(), "WARN", "cancelled before start");
        } else {
            cancelRequests.add(runId);
            runs.requestCancel(runId);
            runs.addEvent(runId, Instant.now(), "WARN", "cancellation requested; destroying runner process");
        }
        return runs.find(runId).orElseThrow();
    }

    boolean isCancelRequested(UUID runId) {
        return cancelRequests.contains(runId);
    }

    void clearCancel(UUID runId) {
        cancelRequests.remove(runId);
    }
}
