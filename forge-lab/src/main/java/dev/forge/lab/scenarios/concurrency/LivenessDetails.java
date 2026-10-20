package dev.forge.lab.scenarios.concurrency;

import dev.forge.lab.concurrency.ControlledExecution.Step;
import dev.forge.lab.concurrency.LivenessExplorer.Report;
import dev.forge.lab.concurrency.LivenessExplorer.Witness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serialisable view of a {@link Report} for the {@code liveness} result detail. */
final class LivenessDetails {

    private static final int MAX_STEPS = 300;

    private LivenessDetails() {
    }

    static Map<String, Object> of(Report r, Map<String, Object> expected) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("threads", r.threads());
        m.put("states", r.states());
        m.put("edges", r.edges());
        m.put("executions", r.executions());
        m.put("complete", r.complete());
        m.put("maxStates", r.maxStates());
        m.put("safetyViolations", r.safetyViolations());
        m.put("firstSafetyViolation", r.firstSafetyViolation());
        m.put("safetyTrace", steps(r.safetyTrace()));
        m.put("deadlocks", r.deadlocks());
        m.put("deadlockTrace", steps(r.deadlockTrace()));
        m.put("livelock", witness(r.livelock()));
        Map<String, Object> starvation = new LinkedHashMap<>();
        r.starvation().forEach((thread, w) -> starvation.put(thread, witness(w)));
        m.put("starvation", starvation);
        m.put("error", r.error());
        m.put("elapsedMs", r.elapsedNanos() / 1e6);
        m.put("expected", expected);
        m.put("fairness", "weak fairness: a cycle counts only if every running thread takes a step in it or is blocked somewhere in it");
        m.put("granularity", "yield points before each shared read, write, CAS, atomic operation and lock action; sequential consistency assumed");
        return m;
    }

    private static Map<String, Object> witness(Witness w) {
        if (w == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prefix", steps(w.prefix()));
        m.put("cycle", steps(w.cycle()));
        m.put("prefixLength", w.prefix().size());
        m.put("cycleLength", w.cycle().size());
        return m;
    }

    private static List<Step> steps(List<Step> steps) {
        return steps == null ? null : steps.subList(0, Math.min(steps.size(), MAX_STEPS));
    }
}
