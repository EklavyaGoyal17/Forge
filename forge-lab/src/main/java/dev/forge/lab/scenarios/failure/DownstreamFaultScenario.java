package dev.forge.lab.scenarios.failure;

import dev.forge.lab.performance.Histograms;
import dev.forge.lab.performance.LatencyRecorder;
import dev.forge.lab.performance.NanoClock;
import dev.forge.lab.performance.OpenLoopGenerator;
import dev.forge.lab.scenario.Lab;
import dev.forge.lab.scenario.MetricSpec;
import dev.forge.lab.scenario.MetricSpec.Direction;
import dev.forge.lab.scenario.ParameterSpec;
import dev.forge.lab.scenario.RunContext;
import dev.forge.lab.scenario.RunSpec;
import dev.forge.lab.scenario.Scenario;
import dev.forge.lab.scenario.ScenarioDescriptor;
import dev.forge.lab.scenario.ScenarioResult;
import dev.forge.lab.scenario.ScenarioResult.Trial;
import dev.forge.lab.scenario.VariantSpec;
import dev.forge.lab.scenarios.failure.InventoryService.Fault;
import dev.forge.lab.scenarios.failure.ResilientClient.Policy;
import org.HdrHistogram.Histogram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Failure Lab: an unstable dependency and the caller's resilience policy.
 *
 * <p>Each trial starts a fresh inventory service inside the runner JVM, drives its caller with an
 * open-model load, injects one fault for a planned window, and records — per time window — the
 * caller's success rate and latency, attempts sent to the dependency, the dependency's backlog and
 * the circuit breaker's state. Recovery time is measured from the end of the fault.
 */
public final class DownstreamFaultScenario implements Scenario {

    public static final String ID = "failure.unstable-dependency";
    private static final String CLIENT = ResilientClient.class.getName() + "#";
    private static final String BREAKER = CircuitBreaker.class.getName() + "#";
    /** Upper bound on downstream attempts per run, so a retry storm cannot exhaust local ports. */
    static final long MAX_ATTEMPTS_PER_RUN = 40_000;
    private static final long TRIAL_OVERHEAD_NANOS = 1_000_000_000L;

    private static final ScenarioDescriptor DESCRIPTOR = new ScenarioDescriptor(
            ID, 1, Lab.FAILURE,
            "Unstable dependency: timeouts, retries and circuit breakers",
            "A caller depends on an inventory service running inside the runner JVM. Inject latency, HTTP 500s, dropped connections or a restart for a planned window and watch what each resilience policy does to success rate, latency, load on the dependency and recovery.",
            "When a dependency slows down or fails for a while, which client policy keeps users served — and which one turns a two-second blip into an outage that outlives the fault?",
            null,
            List.of(
                    ParameterSpec.choice("fault", "Fault", "latency", List.of("latency", "errors", "drop", "refuse", "none"),
                            "latency: every request slower. errors: a share of requests answers HTTP 500. drop: connections closed without a response. refuse: the service stops listening, then restarts on a new port. none: control run."),
                    ParameterSpec.integer("faultLatencyMs", "Injected latency", 800, 1, 5_000, "ms", "Extra time per request while the latency fault is active."),
                    ParameterSpec.integer("errorPercent", "Error share", 50, 1, 100, "%", "Share of requests answered with HTTP 500 while the error fault is active."),
                    ParameterSpec.integer("faultStartMs", "Fault starts", 1_000, 0, 20_000, "ms", "Into the sustained phase."),
                    ParameterSpec.integer("faultDurationMs", "Fault lasts", 2_000, 100, 20_000, "ms", "How long the fault stays active."),
                    ParameterSpec.integer("rate", "Offered rate", 100, 5, 500, "req/s", "Constant arrival rate of caller requests (open model)."),
                    ParameterSpec.integer("warmupMs", "Warm-up phase", 1_000, 0, 5_000, "ms", "Healthy load before measurement starts."),
                    ParameterSpec.integer("durationMs", "Sustained phase", 6_000, 1_000, 30_000, "ms", "Measured phase; the fault happens inside it."),
                    ParameterSpec.integer("downstreamWorkers", "Dependency workers", 4, 1, 64, "threads", "Worker threads of the inventory service."),
                    ParameterSpec.integer("downstreamLatencyMs", "Dependency latency", 10, 0, 500, "ms", "Normal service time per request. Healthy capacity = workers ÷ latency."),
                    ParameterSpec.integer("attemptTimeoutMs", "Attempt timeout", 200, 10, 5_000, "ms", "Per-attempt timeout of the policies that set one."),
                    ParameterSpec.integer("maxRetries", "Max retries", 3, 0, 5, "retries", "Retries after the first attempt, for the retrying policies."),
                    ParameterSpec.integer("backoffMs", "Backoff base", 50, 1, 2_000, "ms", "Backoff before retry n is random in [0, base × 2ⁿ⁻¹) (full jitter)."),
                    ParameterSpec.integer("requestTimeoutMs", "Request deadline", 2_000, 100, 10_000, "ms", "Overall deadline per caller request, from its intended send time. No attempt runs past it."),
                    ParameterSpec.integer("connections", "Max requests in flight", 512, 1, 2_000, "requests", "Caller-side limit on outstanding requests; when reached, the generator waits and that wait counts as latency.")),
            List.of(
                    new VariantSpec("no-timeout", "No timeout, no retry",
                            "One attempt that may take until the request deadline.", null, CLIENT + "attempt"),
                    new VariantSpec("timeout", "Timeout, no retry",
                            "One attempt with a short timeout: fails fast instead of waiting.", null, CLIENT + "attempt"),
                    new VariantSpec("retry-immediate", "Timeout + immediate retries",
                            "Retry every failure straight away, up to maxRetries.", null, CLIENT + "retry"),
                    new VariantSpec("retry-backoff", "Timeout + backoff with jitter",
                            "Retries after a random, exponentially growing delay.", null, CLIENT + "retry"),
                    new VariantSpec("retry-budget", "Backoff + retry budget",
                            "Backoff with jitter, but retries limited to 10% of requests (token bucket), as in gRPC and Finagle.", null, CLIENT + "retry"),
                    new VariantSpec("circuit-breaker", "Backoff + circuit breaker",
                            "Backoff with jitter behind a breaker: opens at ≥ 50% failures of the last 20 calls, fails fast for 1 s, then probes with 3 calls.", null, BREAKER + "circuit-breaker-state")),
            List.of(
                    new MetricSpec("successPercent", "Success", "%", Direction.HIGHER_IS_BETTER, "Measured caller requests that got HTTP 200 within their deadline."),
                    new MetricSpec("faultSuccessPercent", "Success during fault", "%", Direction.HIGHER_IS_BETTER, "Share of completions that were successful in the time windows overlapping the fault."),
                    new MetricSpec("recoveryMs", "Recovery time", "ms", Direction.LOWER_IS_BETTER,
                            "From the end of the fault to the first of three consecutive windows with ≥ 95% success and p99 within 2× the pre-fault p99. Resolution: one window."),
                    new MetricSpec("latencyP50Ms", "Caller p50 latency", "ms", Direction.LOWER_IS_BETTER, "Median caller latency from the intended send time."),
                    new MetricSpec("latencyP99Ms", "Caller p99 latency", "ms", Direction.LOWER_IS_BETTER, "99th percentile caller latency from the intended send time."),
                    new MetricSpec("amplification", "Load amplification", "attempts/request", Direction.LOWER_IS_BETTER, "Attempts sent to the dependency per caller request in the sustained phase."),
                    new MetricSpec("peakBacklog", "Peak dependency backlog", "requests", Direction.LOWER_IS_BETTER, "Most requests queued or in service at the dependency at once."),
                    new MetricSpec("wastedPercent", "Wasted work", "%", Direction.LOWER_IS_BETTER, "Requests the dependency finished after the caller had given up, as a share of requests it received."),
                    new MetricSpec("shortCircuitedPercent", "Short-circuited", "%", Direction.NEUTRAL, "Caller requests failed fast by an open breaker."),
                    new MetricSpec("breakerOpenMs", "Breaker open", "ms", Direction.NEUTRAL, "Total time the breaker spent open."),
                    new MetricSpec("retriesPerRequest", "Retries per request", "", Direction.NEUTRAL, "Retries sent per caller request.")),
            "successPercent",
            new ScenarioDescriptor.TrialPolicy(3, 10, 0, 2),
            300,
            """
            A dependency that slows down does not only fail its own requests. Every caller thread, connection and \
            queue slot that waits for it is unavailable for other work, and work that times out on the caller is \
            usually *still being done* by the dependency — the server does not know the caller has gone.

            **Timeouts** bound how long a caller waits, but on their own they do not reduce the load on the \
            dependency. **Retries** recover from brief, random failures — and multiply the load exactly when the \
            dependency is already overloaded. With immediate retries a slow dependency receives up to \
            (1 + maxRetries)× its normal traffic; if that exceeds its healthy capacity, its backlog keeps growing \
            *after the fault has ended*, every attempt waits behind it and times out, and those time-outs cause more \
            retries. That self-sustaining state is a **metastable failure**: the trigger is gone but the outage is not.

            **Backoff with jitter** spreads retries out but does not limit their number. A **retry budget** caps \
            retries at a fraction of normal traffic, so the amplification cannot exceed (1 + budget). A **circuit \
            breaker** stops calling a dependency that is mostly failing, which both fails fast and gives the \
            dependency time to drain; its half-open probes detect recovery.

            The timeline shows what happened in each window: success rate, latency, attempts sent, the dependency's \
            backlog, and when the breaker was open.""",
            List.of(
                    "The caller, the dependency and the load generator share one JVM and its CPUs; client-side work and GC appear in measured latency.",
                    "Traffic is over loopback TCP: no network latency, loss or bandwidth limit. Injected faults are the only network-like behaviour, and they are simulated by the dependency itself.",
                    "The dependency is the JDK's com.sun.net.httpserver with a fixed worker pool and an unbounded queue — a common default, and the reason backlog grows without bound.",
                    "Whether immediate retries become metastable depends on headroom: (1 + maxRetries) × offered rate versus workers ÷ latency. With more headroom the same policy recovers; the timeline makes the ratio visible.",
                    "Recovery time has the resolution of one timeline window and depends on the 95% and 2×-p99 thresholds, which are conventions, not laws.",
                    "Each timed-out attempt closes a TCP connection, so retry storms consume local ports; runs are capped at " + MAX_ATTEMPTS_PER_RUN + " planned attempts.",
                    "The breaker's settings (20-call window, 50%, 1 s open, 3 probes) are fixed; real deployments tune them, and a breaker that opens too eagerly fails requests that would have succeeded."));

    @Override
    public ScenarioDescriptor descriptor() {
        return DESCRIPTOR;
    }

    static Policy policy(String variant, long attemptTimeoutNanos, int maxRetries, long backoffNanos) {
        return switch (variant) {
            case "no-timeout" -> new Policy(0, 0, 0, -1, false);
            case "timeout" -> new Policy(attemptTimeoutNanos, 0, 0, -1, false);
            case "retry-immediate" -> new Policy(attemptTimeoutNanos, maxRetries, 0, -1, false);
            case "retry-backoff" -> new Policy(attemptTimeoutNanos, maxRetries, backoffNanos, -1, false);
            case "retry-budget" -> new Policy(attemptTimeoutNanos, maxRetries, backoffNanos, 0.1, false);
            case "circuit-breaker" -> new Policy(attemptTimeoutNanos, maxRetries, backoffNanos, -1, true);
            default -> throw new IllegalArgumentException("unknown variant " + variant);
        };
    }

    private record Config(Fault fault, long faultLatencyNanos, double errorFraction, long faultStartNanos, long faultEndNanos,
                          double rate, long warmupNanos, long measureNanos, int workers, long baseLatencyNanos,
                          long requestTimeoutNanos, int connections, long windowNanos) {
    }

    @Override
    public ScenarioResult run(RunSpec spec, RunContext ctx) throws Exception {
        Fault fault = Fault.valueOf(spec.stringParam("fault").toUpperCase(Locale.ROOT));
        long warmup = spec.longParam("warmupMs") * 1_000_000L;
        long measure = spec.longParam("durationMs") * 1_000_000L;
        long faultStart = warmup + spec.longParam("faultStartMs") * 1_000_000L;
        long faultEnd = faultStart + spec.longParam("faultDurationMs") * 1_000_000L;
        if (fault != Fault.NONE && faultEnd > warmup + measure) {
            throw new IllegalArgumentException("the fault must end inside the sustained phase (fault ends at "
                    + (faultEnd - warmup) / 1_000_000 + " ms, sustained phase lasts " + measure / 1_000_000 + " ms)");
        }
        double rate = spec.longParam("rate");
        long requestTimeout = spec.longParam("requestTimeoutMs") * 1_000_000L;
        Policy policy = policy(spec.variant(), spec.longParam("attemptTimeoutMs") * 1_000_000L, spec.intParam("maxRetries"),
                spec.longParam("backoffMs") * 1_000_000L);
        int totalTrials = spec.warmupTrials() + spec.trials();
        long plannedAttempts = (long) Math.ceil(rate * (warmup + measure) / 1e9) * (1 + policy.maxRetries()) * totalTrials;
        if (plannedAttempts > MAX_ATTEMPTS_PER_RUN) {
            throw new IllegalArgumentException("up to " + plannedAttempts + " downstream attempts planned; the limit is "
                    + MAX_ATTEMPTS_PER_RUN + " per run — lower the rate, durations, retries or trials");
        }
        long perTrial = warmup + measure + requestTimeout + OpenLoopGenerator.DRAIN_GRACE_NANOS + TRIAL_OVERHEAD_NANOS;
        if ((double) perTrial * totalTrials > ctx.deadlineNanos() - System.nanoTime()) {
            throw new IllegalArgumentException("the trials do not fit in the run timeout; increase it or reduce trials/durations");
        }
        long window = windowNanos(warmup + measure + requestTimeout);
        Config c = new Config(fault, spec.longParam("faultLatencyMs") * 1_000_000L, spec.longParam("errorPercent") / 100.0,
                faultStart, faultEnd, rate, warmup, measure, spec.intParam("downstreamWorkers"),
                spec.longParam("downstreamLatencyMs") * 1_000_000L, requestTimeout, spec.intParam("connections"), window);
        double capacity = c.baseLatencyNanos() == 0 ? Double.POSITIVE_INFINITY : c.workers() * 1e9 / c.baseLatencyNanos();
        ctx.info(String.format(Locale.ROOT, "dependency capacity %.0f req/s healthy; offered %.0f req/s; up to %d× amplification with %d retries",
                capacity, rate, 1 + policy.maxRetries(), policy.maxRetries()));

        ScenarioResult.Builder result = ScenarioResult.builder();
        List<Map<String, Object>> trialDetails = new ArrayList<>();
        for (int t = 0; t < totalTrials; t++) {
            ctx.checkCancelled();
            boolean warm = t < spec.warmupTrials();
            TrialData d = runTrial(c, policy, spec.seed() + t, ctx);
            ctx.progress(t + 1, totalTrials, warm ? "warm-up" : "measured");
            if (warm) {
                continue;
            }
            int index = t - spec.warmupTrials();
            result.trial(new Trial(index, d.metrics, null, d.note));
            Map<String, Object> td = new LinkedHashMap<>(d.details);
            td.put("trial", index);
            trialDetails.add(td);
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("policy", Map.of("attemptTimeoutMs", policy.attemptTimeoutNanos() / 1e6, "maxRetries", policy.maxRetries(),
                "backoffBaseMs", policy.backoffBaseNanos() / 1e6, "retryBudget", policy.retryBudget(), "breaker", policy.breaker()));
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("fault", spec.stringParam("fault"));
        cfg.put("faultStartMs", faultStart / 1e6);
        cfg.put("faultEndMs", faultEnd / 1e6);
        cfg.put("measureStartMs", warmup / 1e6);
        cfg.put("measureEndMs", (warmup + measure) / 1e6);
        cfg.put("windowMs", window / 1e6);
        cfg.put("rate", rate);
        cfg.put("downstreamWorkers", c.workers());
        cfg.put("downstreamLatencyMs", c.baseLatencyNanos() / 1e6);
        cfg.put("healthyCapacityRps", Double.isInfinite(capacity) ? null : capacity);
        cfg.put("requestTimeoutMs", requestTimeout / 1e6);
        cfg.put("bindAddress", "127.0.0.1");
        details.put("config", cfg);
        details.put("trials", trialDetails);
        result.detail("failure", details);
        observe(result, c, policy, capacity, trialDetails);
        return result.build();
    }

    private static final class TrialData {
        Map<String, Double> metrics;
        Map<String, Object> details;
        String note;
    }

    private static TrialData runTrial(Config c, Policy policy, long seed, RunContext ctx) throws Exception {
        long span = c.warmupNanos() + c.measureNanos() + c.requestTimeoutNanos() + OpenLoopGenerator.DRAIN_GRACE_NANOS;
        int windows = (int) (span / c.windowNanos()) + 2;
        AtomicIntegerArray backlog = new AtomicIntegerArray(windows);
        AtomicIntegerArray breakerOpen = new AtomicIntegerArray(windows);   // 0 closed, 1 half-open, 2 open (max seen)
        ScheduledExecutorService control = Executors.newScheduledThreadPool(2, r -> {
            Thread th = new Thread(r, "fault-control");
            th.setDaemon(true);
            return th;
        });
        try (InventoryService service = new InventoryService(c.workers(), c.baseLatencyNanos(), c.faultLatencyNanos(), c.errorFraction(), seed)) {
            long t0 = System.nanoTime();
            try (ResilientClient client = new ResilientClient(policy, service::address, seed, t0, c.windowNanos(), windows)) {
                if (c.fault() != Fault.NONE) {
                    control.schedule(() -> service.inject(c.fault()), c.faultStartNanos(), TimeUnit.NANOSECONDS);
                    control.schedule(() -> {
                        try {
                            service.clear();
                        } catch (java.io.IOException e) {
                            ctx.warn("restarting the inventory service failed: " + e.getMessage());
                        }
                    }, c.faultEndNanos(), TimeUnit.NANOSECONDS);
                }
                control.scheduleAtFixedRate(() -> {
                    int w = (int) ((System.nanoTime() - t0) / c.windowNanos());
                    if (w >= 0 && w < windows) {
                        backlog.accumulateAndGet(w, service.inFlight(), Math::max);
                        CircuitBreaker.State s = client.breakerState();
                        int code = s == null ? 0 : switch (s) { case CLOSED -> 0; case HALF_OPEN -> 1; case OPEN -> 2; };
                        breakerOpen.accumulateAndGet(w, code, Math::max);
                    }
                }, 0, Math.max(1_000_000, c.windowNanos() / 5), TimeUnit.NANOSECONDS);

                OpenLoopGenerator.Plan plan = new OpenLoopGenerator.Plan(c.rate(), c.warmupNanos(), c.measureNanos(),
                        c.requestTimeoutNanos(), c.connections(), c.windowNanos());
                OpenLoopGenerator.Result r = OpenLoopGenerator.run(plan, NanoClock.SYSTEM,
                        (i, measured, timeout) -> client.call(System.nanoTime() + timeout), ctx::checkCancelled);
                control.shutdownNow();
                return summarise(c, r, client.stats(), service.stats(), backlog, breakerOpen, t0);
            }
        } finally {
            control.shutdownNow();
        }
    }

    private static TrialData summarise(Config c, OpenLoopGenerator.Result r, ResilientClient.Stats cs, InventoryService.Stats ss,
                                       AtomicIntegerArray backlog, AtomicIntegerArray breaker, long t0) {
        LatencyRecorder rec = r.recorder();
        LatencyRecorder.Counts n = rec.counts();
        Histogram latency = rec.corrected();
        double windowMs = c.windowNanos() / 1e6;
        List<Map<String, Object>> series = rec.series();
        int measureFrom = (int) (c.warmupNanos() / c.windowNanos());
        int measureTo = (int) ((c.warmupNanos() + c.measureNanos()) / c.windowNanos());
        long measuredAttempts = 0;
        for (int w = measureFrom; w < Math.min(measureTo, cs.attemptsByWindow().length); w++) {
            measuredAttempts += cs.attemptsByWindow()[w];
        }
        int peakBacklog = 0;
        for (int i = 0; i < series.size(); i++) {
            Map<String, Object> p = series.get(i);
            int b = i < backlog.length() ? backlog.get(i) : 0;
            peakBacklog = Math.max(peakBacklog, b);
            p.put("attempts", i < cs.attemptsByWindow().length ? cs.attemptsByWindow()[i] : 0);
            p.put("backlog", b);
            p.put("breaker", i < breaker.length() ? breaker.get(i) : 0);
            long ok = ((Number) p.get("completed")).longValue();
            long err = ((Number) p.get("errors")).longValue();
            p.put("successPercent", ok + err == 0 ? null : 100.0 * ok / (ok + err));
        }

        Map<String, Double> m = new LinkedHashMap<>();
        m.put("successPercent", n.offered() == 0 ? 0.0 : round(100.0 * n.ok() / n.offered(), 2));
        if (latency.getTotalCount() > 0) {
            m.put("latencyP50Ms", Histograms.ms(latency.getValueAtPercentile(50)));
            m.put("latencyP99Ms", Histograms.ms(latency.getValueAtPercentile(99)));
        }
        m.put("amplification", n.offered() == 0 ? 0.0 : round((double) measuredAttempts / n.offered(), 3));
        m.put("peakBacklog", (double) peakBacklog);
        m.put("wastedPercent", ss.received() == 0 ? 0.0 : round(100.0 * ss.wasted() / ss.received(), 2));
        m.put("shortCircuitedPercent", r.scheduled() == 0 ? 0.0 : round(100.0 * cs.shortCircuited() / r.scheduled(), 2));
        m.put("retriesPerRequest", r.scheduled() == 0 ? 0.0 : round((double) cs.retries() / r.scheduled(), 3));
        long openNanos = openTime(cs.transitions(), t0, t0 + c.warmupNanos() + c.measureNanos() + c.requestTimeoutNanos());
        if (c.fault() != Fault.NONE || openNanos > 0) {
            m.put("breakerOpenMs", round(openNanos / 1e6, 1));
        }

        String note = null;
        Double recovery = null;
        if (c.fault() != Fault.NONE) {
            double faultStartMs = c.faultStartNanos() / 1e6;
            double faultEndMs = c.faultEndNanos() / 1e6;
            long okF = 0;
            long errF = 0;
            List<Double> baselineP99 = new ArrayList<>();
            for (Map<String, Object> p : series) {
                double t = ((Number) p.get("tMs")).doubleValue();
                if (t + windowMs > faultStartMs && t < faultEndMs) {
                    okF += ((Number) p.get("completed")).longValue();
                    errF += ((Number) p.get("errors")).longValue();
                }
                if (t >= c.warmupNanos() / 1e6 && t + windowMs <= faultStartMs && p.get("p99Ms") != null) {
                    baselineP99.add(((Number) p.get("p99Ms")).doubleValue());
                }
            }
            m.put("faultSuccessPercent", okF + errF == 0 ? 0.0 : round(100.0 * okF / (okF + errF), 2));
            double base = median(baselineP99);
            double limit = Double.isNaN(base) ? Double.POSITIVE_INFINITY : Math.max(2 * base, base + 1);
            double lastIntendedMs = (c.warmupNanos() + c.measureNanos()) / 1e6;
            for (int i = 0; i + 2 < series.size(); i++) {
                double t = ((Number) series.get(i).get("tMs")).doubleValue();
                if (t < faultEndMs) {
                    continue;
                }
                if (t + 3 * windowMs > lastIntendedMs) {
                    break;                                          // not enough load left to judge
                }
                if (healthy(series.get(i), limit) && healthy(series.get(i + 1), limit) && healthy(series.get(i + 2), limit)) {
                    recovery = t - faultEndMs;
                    break;
                }
            }
            if (recovery != null) {
                m.put("recoveryMs", round(recovery, 1));
            } else {
                note = "did not recover before the sustained phase ended";
            }
        }

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("series", series);
        d.put("offered", n.offered());
        d.put("ok", n.ok());
        d.put("httpErrors", n.httpErrors());
        d.put("failures", n.failures());
        d.put("timeouts", n.timeouts());
        d.put("unfinished", r.unfinished());
        d.put("attempts", cs.attempts());
        // received by the dependency minus sent by the policy: positive when the HTTP client resent requests
        // on its own (refused connections never arrive, so this can also be negative)
        d.put("receivedMinusSent", ss.received() - cs.attempts());
        d.put("retries", cs.retries());
        d.put("shortCircuited", cs.shortCircuited());
        d.put("budgetDenied", cs.budgetDenied());
        d.put("downstream", Map.of("received", ss.received(), "served", ss.served(), "errors", ss.errors(),
                "dropped", ss.dropped(), "wasted", ss.wasted(), "restarts", ss.restarts()));
        List<Map<String, Object>> transitions = new ArrayList<>();
        for (CircuitBreaker.Transition tr : cs.transitions()) {
            transitions.add(Map.of("tMs", round((tr.atNanos() - t0) / 1e6, 1), "state", tr.to().name()));
        }
        d.put("breakerTransitions", transitions);
        d.put("recoveryMs", recovery);
        d.put("latency", Histograms.summary(latency));

        TrialData td = new TrialData();
        td.metrics = m;
        td.details = d;
        td.note = note;
        return td;
    }

    private static boolean healthy(Map<String, Object> p, double p99Limit) {
        long ok = ((Number) p.get("completed")).longValue();
        long err = ((Number) p.get("errors")).longValue();
        Object p99 = p.get("p99Ms");
        return ok + err > 0 && ok >= 0.95 * (ok + err) && p99 != null && ((Number) p99).doubleValue() <= p99Limit;
    }

    private static long openTime(List<CircuitBreaker.Transition> transitions, long t0, long end) {
        long total = 0;
        Long openedAt = null;
        for (CircuitBreaker.Transition t : transitions) {
            if (t.to() == CircuitBreaker.State.OPEN && openedAt == null) {
                openedAt = t.atNanos();
            } else if (t.to() != CircuitBreaker.State.OPEN && openedAt != null) {
                total += t.atNanos() - openedAt;
                openedAt = null;
            }
        }
        if (openedAt != null) {
            total += Math.max(0, end - openedAt);
        }
        return total;
    }

    private static void observe(ScenarioResult.Builder result, Config c, Policy policy, double capacity, List<Map<String, Object>> trialDetails) {
        List<Trial> trials = result.trials();
        if (trials.isEmpty()) {
            return;
        }
        double faultCapacity = c.fault() == Fault.LATENCY ? c.workers() * 1e9 / (c.baseLatencyNanos() + c.faultLatencyNanos()) : Double.NaN;
        result.observation(String.format(Locale.ROOT,
                "Offered %.0f req/s; the dependency's healthy capacity is %s req/s (%d workers ÷ %.0f ms)%s.",
                c.rate(), Double.isInfinite(capacity) ? "unbounded" : String.format(Locale.ROOT, "%.0f", capacity), c.workers(), c.baseLatencyNanos() / 1e6,
                Double.isNaN(faultCapacity) ? "" : String.format(Locale.ROOT, " and %.1f req/s while the latency fault is active", faultCapacity)));
        if (policy.maxRetries() > 0 && !Double.isInfinite(capacity)) {
            double worst = c.rate() * (1 + policy.maxRetries());
            result.observation(String.format(Locale.ROOT,
                    "If every attempt fails, %d retries can raise demand to %.0f attempts/s — %.0f%% of the healthy capacity%s.",
                    policy.maxRetries(), worst, 100 * worst / capacity,
                    policy.retryBudget() >= 0 ? String.format(Locale.ROOT, "; the retry budget limits it to about %.0f attempts/s", c.rate() * (1 + policy.retryBudget())) : ""));
        }
        result.observation(String.format(Locale.ROOT, "Median across %d trials: success %.2f%%, p99 %.1f ms, %.2f attempts per request, peak dependency backlog %.0f, wasted work %.1f%%.",
                trials.size(), median(values(trials, "successPercent")), median(values(trials, "latencyP99Ms")),
                median(values(trials, "amplification")), median(values(trials, "peakBacklog")), median(values(trials, "wastedPercent"))));
        if (c.fault() != Fault.NONE) {
            long recovered = trials.stream().filter(t -> t.metrics().containsKey("recoveryMs")).count();
            result.observation(String.format(Locale.ROOT, "During the fault, %.1f%% of completions succeeded (median).",
                    median(values(trials, "faultSuccessPercent"))));
            if (recovered == trials.size()) {
                result.observation(String.format(Locale.ROOT, "Recovered in every trial; median recovery %.0f ms after the fault ended (window %.0f ms).",
                        median(values(trials, "recoveryMs")), c.windowNanos() / 1e6));
            } else {
                result.observation(String.format(Locale.ROOT,
                        "Did not recover before the end of the sustained phase in %d of %d trials, although the fault had ended — the failure outlived its trigger (look for a backlog that keeps growing in the timeline).",
                        trials.size() - recovered, trials.size()));
            }
        }
        long hidden = 0;
        for (Map<String, Object> td : trialDetails) {
            hidden += Math.max(0, ((Number) td.get("receivedMinusSent")).longValue());
        }
        if (hidden > 0) {
            result.observation("The dependency received " + hidden + " more requests than the policy sent: the JDK HttpClient resends an idempotent GET by itself when a pooled connection closes before any response. Retries can hide below your own retry logic.");
        }
        if (policy.breaker()) {
            result.observation(String.format(Locale.ROOT, "The breaker was open for a median of %.0f ms; %.1f%% of requests were short-circuited.",
                    median(values(trials, "breakerOpenMs")), median(values(trials, "shortCircuitedPercent"))));
        }
    }

    /** About 80 windows over the trial, at least 50 ms, rounded up to 10 ms. */
    static long windowNanos(long spanNanos) {
        long ms = Math.max(50, (long) Math.ceil(spanNanos / 1e6 / 80));
        ms = (ms + 9) / 10 * 10;
        return ms * 1_000_000L;
    }

    private static double[] values(List<Trial> trials, String metric) {
        return trials.stream().map(t -> t.metrics().get(metric)).filter(v -> v != null && Double.isFinite(v))
                .mapToDouble(Double::doubleValue).sorted().toArray();
    }

    private static double median(double[] sorted) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        double[] s = sorted.clone();
        Arrays.sort(s);
        return (s[(s.length - 1) / 2] + s[s.length / 2]) / 2;
    }

    private static double median(List<Double> xs) {
        return median(xs.stream().mapToDouble(Double::doubleValue).toArray());
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
