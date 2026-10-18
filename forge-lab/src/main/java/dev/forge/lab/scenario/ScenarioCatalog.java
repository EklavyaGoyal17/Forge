package dev.forge.lab.scenario;

import dev.forge.lab.scenarios.algorithms.LookupScenario;
import dev.forge.lab.scenarios.concurrency.DeadlockScenario;
import dev.forge.lab.scenarios.concurrency.LostUpdateScenario;
import dev.forge.lab.scenarios.concurrency.VisibilityScenario;

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
        register(new LookupScenario());
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
