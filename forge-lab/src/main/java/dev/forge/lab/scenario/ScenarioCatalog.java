package dev.forge.lab.scenario;

import dev.forge.lab.scenarios.algorithms.LookupScenario;
import dev.forge.lab.scenarios.concurrency.BackpressureScenario;
import dev.forge.lab.scenarios.concurrency.DeadlockScenario;
import dev.forge.lab.scenarios.database.IndexLookupScenario;
import dev.forge.lab.scenarios.failure.DownstreamFaultScenario;
import dev.forge.lab.scenarios.database.NPlusOneScenario;
import dev.forge.lab.scenarios.database.PaginationScenario;
import dev.forge.lab.scenarios.database.SqlLostUpdateScenario;
import dev.forge.lab.scenarios.concurrency.LivelockScenario;
import dev.forge.lab.scenarios.concurrency.LostUpdateScenario;
import dev.forge.lab.scenarios.concurrency.StarvationScenario;
import dev.forge.lab.scenarios.concurrency.VisibilityScenario;
import dev.forge.lab.scenarios.jvm.AllocationScenario;
import dev.forge.lab.scenarios.jvm.LockContentionScenario;
import dev.forge.lab.scenarios.jvm.WarmupScenario;
import dev.forge.lab.scenarios.performance.GeneratorCrossCheckScenario;
import dev.forge.lab.scenarios.performance.ThreadPoolStarvationScenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The explicit list of executable scenarios. There is deliberately no classpath scanning: the set
 * of code the runner will execute is reviewable in this one file.
 */
public final class ScenarioCatalog {

    private static final Map<String, Scenario> SCENARIOS = new LinkedHashMap<>();

    static {
        register(new LostUpdateScenario());
        register(new DeadlockScenario());
        register(new VisibilityScenario());
        register(new BackpressureScenario());
        register(new LivelockScenario());
        register(new StarvationScenario());
        register(new LookupScenario());
        register(new ThreadPoolStarvationScenario());
        register(new GeneratorCrossCheckScenario());
        register(new IndexLookupScenario());
        register(new PaginationScenario());
        register(new NPlusOneScenario());
        register(new SqlLostUpdateScenario());
        register(new WarmupScenario());
        register(new AllocationScenario());
        register(new LockContentionScenario());
        register(new DownstreamFaultScenario());
    }

    private ScenarioCatalog() {
    }

    private static void register(Scenario s) {
        if (SCENARIOS.putIfAbsent(s.descriptor().id(), s) != null) {
            throw new IllegalStateException("duplicate scenario id " + s.descriptor().id());
        }
    }

    public static Optional<Scenario> find(String id) {
        return Optional.ofNullable(SCENARIOS.get(id));
    }

    public static List<ScenarioDescriptor> descriptors() {
        return SCENARIOS.values().stream().map(Scenario::descriptor).toList();
    }
}
