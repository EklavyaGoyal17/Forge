package dev.forge.lab.scenario;

/**
 * An executable, parameterised experiment. Implementations run inside the isolated runner JVM.
 *
 * <p>Rules (see docs/SECURITY.md): no network access except to loopback services the scenario
 * starts itself, no file access outside {@link RunContext#workDir()}, no {@code System.exit},
 * and all work must be bounded by {@link RunContext#deadlineNanos()}.
 */
public interface Scenario {

    ScenarioDescriptor descriptor();

    ScenarioResult run(RunSpec spec, RunContext ctx) throws Exception;
}
