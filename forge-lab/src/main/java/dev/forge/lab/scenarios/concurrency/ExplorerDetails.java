package dev.forge.lab.scenarios.concurrency;

import dev.forge.lab.concurrency.ControlledExecution.Execution;
import dev.forge.lab.concurrency.ControlledExecution.Step;
import dev.forge.lab.concurrency.InterleavingExplorer.Report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serialisable view of an explorer report for the {@code explorer} result detail. */
final class ExplorerDetails {

    private static final int MAX_TRACE_STEPS = 400;

    private ExplorerDetails() {
    }

    static Map<String, Object> of(Report r, Object expected) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schedulesExplored", r.schedulesExplored());
        m.put("exhaustive", r.exhaustive());
        m.put("maxSchedules", r.maxSchedules());
        m.put("violations", r.violations());
        m.put("deadlocks", r.deadlocks());
        m.put("stepLimitHits", r.stepLimitHits());
        m.put("errors", r.errors());
        m.put("firstError", r.firstError());
        m.put("outcomes", r.outcomeCounts());
        m.put("expected", expected);
        m.put("elapsedMs", r.elapsedNanos() / 1e6);
        m.put("counterexample", trace(r.firstViolation(), r.firstViolationOutcome()));
        m.put("passingExample", trace(r.firstPass(), r.firstPass() == null ? null : "invariant held"));
        m.put("granularity", "yield points before each shared read, write, CAS, atomic operation and lock action; sequential consistency assumed");
        return m;
    }

    private static Map<String, Object> trace(Execution e, String outcome) {
        if (e == null) {
            return null;
        }
        List<Step> steps = e.steps();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outcome", outcome);
        m.put("executionOutcome", e.outcome().name());
        m.put("waitFor", e.waitFor());
        m.put("totalSteps", steps.size());
        m.put("truncated", steps.size() > MAX_TRACE_STEPS);
        m.put("threads", steps.stream().map(Step::thread).distinct().sorted().toList());
        m.put("steps", steps.subList(0, Math.min(steps.size(), MAX_TRACE_STEPS)));
        return m;
    }
}
