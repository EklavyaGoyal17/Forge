package dev.forge.lab.scenarios.performance;

import dev.forge.lab.performance.CpuWork;
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
import dev.forge.lab.scenarios.performance.TargetService.Design;
import dev.forge.lab.stats.Summary;
import org.HdrHistogram.Histogram;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Performance Lab: an embedded HTTP service with a bounded worker pool under an open-model load.
 *
 * <p>Each trial starts a fresh target service on loopback inside this JVM, drives it at a constant
 * arrival rate for a warm-up phase and a sustained (measured) phase, and records client latency
 * from the intended send time, server service time and queue wait in HdrHistograms.
 */
public final class ThreadPoolStarvationScenario implements Scenario {

    public static final String ID = "performance.thread-pool-starvation";
    private static final String SRC = TargetService.class.getName() + "#";

    private static final Map<String, Design> DESIGNS = Map.of(
            "blocking-pool", Design.BLOCKING_POOL,
            "large-pool", Design.LARGE_POOL,
            "async", Design.ASYNC,
            "virtual-threads", Design.VIRTUAL_THREADS);

    /** Fixed start-up allowance per trial (server start, client creation, JVM noise). */
    private static final long TRIAL_OVERHEAD_NANOS = 1_000_000_000L;

    private static final ScenarioDescriptor DESCRIPTOR = new ScenarioDescriptor(
            ID, 3, Lab.PERFORMANCE,           // v2: TCP_NODELAY (Nagle + delayed ACK added up to 40 ms on Linux); v3: profiling
            "Thread-pool starvation under open-model HTTP load",
            "An embedded HTTP service on loopback does CPU work and then waits on a slow downstream. Drive it at a constant arrival rate and watch what a small blocking pool, a bigger pool, a non-blocking design and virtual threads do to throughput, tail latency and queueing.",
            "When requests block a worker thread on a slow downstream, does throughput plateau at workers ÷ service time (Little's law), and what happens to tail latency once the offered rate exceeds it?",
            null,
            List.of(
                    ParameterSpec.integer("rate", "Offered rate", 300, 10, 2_000, "req/s",
                            "Constant arrival rate. Requests are due on a fixed schedule whether or not earlier ones have completed (open model)."),
                    ParameterSpec.integer("warmupMs", "Warm-up phase", 1_000, 0, 5_000, "ms",
                            "Load applied at the same rate before measurement starts in every trial; its requests are not measured."),
                    ParameterSpec.integer("durationMs", "Sustained phase", 3_000, 500, 10_000, "ms",
                            "Measured phase of each trial. Requests whose intended send time falls in it are measured."),
                    ParameterSpec.integer("poolSize", "Worker pool size", 4, 1, 32, "threads",
                            "Server worker threads (the large-pool variant uses 8× this, at most 256; virtual threads have no pool)."),
                    ParameterSpec.integer("downstreamMs", "Downstream latency", 20, 0, 200, "ms",
                            "Simulated downstream call per request: a sleep (blocking variants) or a timer callback (non-blocking variant)."),
                    ParameterSpec.integer("cpuWorkUs", "CPU work per request", 200, 0, 1_000, "µs",
                            "Calibrated CPU-bound work per request (nominal duration on an idle core)."),
                    ParameterSpec.integer("connections", "Max connections", 64, 1, 256, "connections",
                            "Client connection limit = maximum requests in flight. When all are busy, the generator waits; that wait counts towards latency."),
                    ParameterSpec.integer("requestTimeoutMs", "Request timeout", 2_000, 100, 5_000, "ms",
                            "Measured from the intended send time. Requests without a response by then are counted as errors."),
                    ParameterSpec.bool("profile", "Profile", true,
                            "Record Java Flight Recorder and sample the worker threads' states every 10 ms during the sustained phase. Adds a little overhead; switch off to measure without it.")),
            List.of(
                    new VariantSpec("blocking-pool", "Blocking handler, small pool",
                            "Each request holds one of poolSize worker threads for its CPU work and its whole downstream wait.", null, SRC + "blocking-pool"),
                    new VariantSpec("large-pool", "Blocking handler, 8× pool",
                            "The same blocking handler with 8 × poolSize worker threads (at most 256).", null, SRC + "large-pool"),
                    new VariantSpec("async", "Non-blocking downstream",
                            "poolSize workers do the CPU work; the downstream call completes via a callback, so no worker waits for it.", null, SRC + "async"),
                    new VariantSpec("virtual-threads", "Blocking handler, virtual threads",
                            "The same blocking handler, one virtual thread per request; a sleeping virtual thread releases its carrier thread.", null, SRC + "virtual-threads")),
            List.of(
                    new MetricSpec("latencyP99Ms", "Client p99 latency", "ms", Direction.LOWER_IS_BETTER,
                            "99th percentile of client-observed latency, measured from each request's intended send time (coordinated-omission corrected)."),
                    new MetricSpec("latencyP50Ms", "Client p50 latency", "ms", Direction.LOWER_IS_BETTER, "Median client latency from the intended send time."),
                    new MetricSpec("latencyP90Ms", "Client p90 latency", "ms", Direction.LOWER_IS_BETTER, "90th percentile client latency from the intended send time."),
                    new MetricSpec("latencyP95Ms", "Client p95 latency", "ms", Direction.LOWER_IS_BETTER, "95th percentile client latency from the intended send time."),
                    new MetricSpec("latencyMaxMs", "Client max latency", "ms", Direction.LOWER_IS_BETTER, "Largest client latency from the intended send time."),
                    new MetricSpec("uncorrectedP99Ms", "Client p99 (from actual send)", "ms", Direction.LOWER_IS_BETTER,
                            "p99 measured from when the request was actually sent — what a generator that ignores coordinated omission reports."),
                    new MetricSpec("serviceP50Ms", "Server service time p50", "ms", Direction.LOWER_IS_BETTER, "Worker start → response written, measured by the server."),
                    new MetricSpec("serviceP99Ms", "Server service time p99", "ms", Direction.LOWER_IS_BETTER, "Worker start → response written, measured by the server."),
                    new MetricSpec("queueWaitP50Ms", "Server queue wait p50", "ms", Direction.LOWER_IS_BETTER, "Request handed to the worker executor → a worker starts it."),
                    new MetricSpec("queueWaitP99Ms", "Server queue wait p99", "ms", Direction.LOWER_IS_BETTER, "Request handed to the worker executor → a worker starts it."),
                    new MetricSpec("achievedRps", "Achieved throughput", "req/s", Direction.HIGHER_IS_BETTER,
                            "Successful responses completed during the sustained phase ÷ its duration."),
                    new MetricSpec("offeredRps", "Offered rate", "req/s", Direction.NEUTRAL, "Requests scheduled per second (the rate parameter)."),
                    new MetricSpec("errorPercent", "Error rate", "%", Direction.LOWER_IS_BETTER,
                            "Measured requests that timed out, failed, or returned a non-200 status."),
                    new MetricSpec("timeouts", "Timeouts", "requests", Direction.LOWER_IS_BETTER, "Measured requests without a response within the request timeout."),
                    new MetricSpec("occupancyMs", "Worker time per request", "ms", Direction.NEUTRAL,
                            "Mean time a pool thread was occupied per measured request (pool designs only)."),
                    new MetricSpec("littleBoundRps", "Little's-law capacity", "req/s", Direction.NEUTRAL,
                            "workers ÷ worker time per request: the most this pool can complete per second (pool designs only)."),
                    new MetricSpec("timerLagP99Ms", "Generator timer lag p99", "ms", Direction.LOWER_IS_BETTER,
                            "How late the load generator woke up for a scheduled send (OS timer granularity, CPU contention)."),
                    new MetricSpec("cpuUtilizationPercent", "Process CPU utilisation", "%", Direction.NEUTRAL,
                            "Runner-process CPU time ÷ (wall time × available processors) during the sustained phase — client, server and GC together."),
                    new MetricSpec("workersBlockedPercent", "Workers blocked in a request", "%", Direction.NEUTRAL,
                            "Share of worker-thread samples inside a request but not running — sleeping, parked or blocked, e.g. waiting on the downstream call. Platform-thread pools only."),
                    new MetricSpec("workersIdlePercent", "Workers idle", "%", Direction.NEUTRAL,
                            "Share of worker-thread samples idle in the pool, waiting for a request: spare capacity. Platform-thread pools only.")),
            "latencyP99Ms",
            new ScenarioDescriptor.TrialPolicy(5, 10, 1, 3),
            300,
            """
            Little's law says that in a stable system the average number of requests inside it equals arrival rate × time \
            in system (L = λW). Turned around, a pool of N threads that each hold a request for S seconds can complete at \
            most N ÷ S requests per second. With 4 workers and a 20 ms downstream call that is about 200 req/s — no matter \
            how many CPU cores are idle, because the threads are not computing, they are waiting.

            Offer more than that and the excess has nowhere to go but a queue. In an *open* model, where requests keep \
            arriving on schedule as independent users do, the queue — and with it latency — grows for as long as the \
            overload lasts, until clients time out. Throughput stays flat at the plateau. A bigger pool raises the \
            plateau (at the cost of threads); a non-blocking design stops threads from waiting at all, so the bound \
            becomes CPU work, not downstream latency; virtual threads keep the blocking code but make a waiting thread cheap.

            Latency is measured from the moment each request *should* have been sent. When the generator runs out of \
            connections it has to delay sends; measuring from the actual send would hide that delay, which is exactly \
            the time the slowest users spent waiting. FORGE records both so the difference (coordinated omission) is visible.""",
            List.of(
                    "Client and server run in the same JVM and share its CPU, heap and garbage collector: client work and GC pauses appear in the measured latency.",
                    "Traffic is over loopback TCP — no NIC, no network latency or loss. Absolute numbers are lower than across a network; the shape of the curves is the point.",
                    "The target is the JDK's built-in com.sun.net.httpserver (one selector thread). Results characterise these designs on this server, not on Netty, Tomcat or Jetty.",
                    "The downstream call is simulated (Thread.sleep or a timer). OS timer granularity — coarse on Windows — can stretch it by up to a few milliseconds; the server's measured service time shows the real value.",
                    "The generator also relies on OS timers; its wake-up lag is reported per trial (timerLagP99Ms) and is included in client latency.",
                    "CPU work is calibrated once on an idle core; under contention the same work takes longer, by design.",
                    "Timeouts are recorded at the moment the client gave up, which is a lower bound of their real latency.",
                    "A percentile is only resolvable from enough samples: p99.9 needs at least 1 000 measured requests, otherwise it is effectively the maximum.",
                    "Per-trial percentiles are summarised across trials by their median; percentiles are never averaged. Aggregate percentiles come from merging the raw histograms."));

    @Override
    public ScenarioDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ScenarioResult run(RunSpec spec, RunContext ctx) throws Exception {
        Design design = DESIGNS.get(spec.variant());
        if (design == null) {
            throw new IllegalArgumentException("unknown variant " + spec.variant());
        }
        double rate = spec.longParam("rate");
        long warmupNanos = spec.longParam("warmupMs") * 1_000_000L;
        long measureNanos = spec.longParam("durationMs") * 1_000_000L;
        long timeoutNanos = spec.longParam("requestTimeoutMs") * 1_000_000L;
        int connections = spec.intParam("connections");
        int poolSize = spec.intParam("poolSize");
        long downstreamNanos = spec.longParam("downstreamMs") * 1_000_000L;
        long cpuWorkUs = spec.longParam("cpuWorkUs");

        int totalTrials = spec.warmupTrials() + spec.trials();
        long perTrialBound = warmupNanos + measureNanos + timeoutNanos + OpenLoopGenerator.DRAIN_GRACE_NANOS + TRIAL_OVERHEAD_NANOS;
        long available = ctx.deadlineNanos() - System.nanoTime();
        if ((double) perTrialBound * totalTrials > available) {
            throw new IllegalArgumentException(String.format(
                    "%d trials × up to %.1f s each do not fit in the remaining %.0f s of the run timeout; increase the timeout or reduce trials/durations",
                    totalTrials, perTrialBound / 1e9, available / 1e9));
        }

        boolean profile = spec.boolParam("profile");
        double itersPerMicro = CpuWork.calibrateIterationsPerMicro();
        long cpuIterations = Math.round(cpuWorkUs * itersPerMicro);
        TargetService.Config config = new TargetService.Config(design, poolSize, cpuIterations, downstreamNanos);
        long windowNanos = windowNanos(warmupNanos + measureNanos + timeoutNanos + OpenLoopGenerator.DRAIN_GRACE_NANOS);
        OpenLoopGenerator.Plan plan = new OpenLoopGenerator.Plan(rate, warmupNanos, measureNanos, timeoutNanos, connections, windowNanos);
        ctx.info(String.format("CPU work calibrated: %.1f iterations/µs → %d iterations for %d µs", itersPerMicro, cpuIterations, cpuWorkUs));
        ctx.info(String.format("open model: %.0f req/s, %d ms warm-up + %d ms sustained per trial, ≤ %d connections; %s",
                rate, warmupNanos / 1_000_000, measureNanos / 1_000_000, connections, spec.variant()));

        ScenarioResult.Builder result = ScenarioResult.builder();
        List<Map<String, Object>> trialDetails = new ArrayList<>();
        Histogram aggClient = Histograms.newMicros();
        Histogram aggUncorrected = Histograms.newMicros();
        Histogram aggService = Histograms.newMicros();
        Histogram aggQueue = Histograms.newMicros();
        int workers = -1;
        long totalOffered = 0;
        long totalErrors = 0;
        long totalTimeouts = 0;

        dev.forge.lab.jvm.JfrCapture capture = profile
                ? dev.forge.lab.jvm.JfrCapture.start(ctx.workDir(), "http", dev.forge.lab.jvm.JfrCapture.Settings.defaults()) : null;
        for (int t = 0; t < totalTrials; t++) {
            ctx.checkCancelled();
            boolean warmupTrial = t < spec.warmupTrials();
            if (capture != null && !warmupTrial) {
                dev.forge.lab.jvm.JfrMarker.mark("trial-" + (t - spec.warmupTrials()) + " start");
            }
            TrialData data = runTrial(config, plan, ctx, profile);
            if (capture != null && !warmupTrial) {
                dev.forge.lab.jvm.JfrMarker.mark("trial-" + (t - spec.warmupTrials()) + " end");
            }
            workers = data.workers();
            if (warmupTrial) {
                ctx.progress(t + 1, totalTrials, "warm-up");
                continue;
            }
            int index = t - spec.warmupTrials();
            LatencyRecorder rec = data.result().recorder();
            LatencyRecorder.Counts c = rec.counts();
            Histogram client = rec.corrected();
            Histogram uncorrected = rec.uncorrected();
            TargetService.Stats server = data.server();
            aggClient.add(client);
            aggUncorrected.add(uncorrected);
            aggService.add(server.serviceTime());
            aggQueue.add(server.queueWait());
            totalOffered += c.offered();
            totalErrors += c.errors() + data.result().unfinished();
            totalTimeouts += c.timeouts();

            Map<String, Double> m = new LinkedHashMap<>();
            putPercentiles(m, "latency", client, true);
            if (uncorrected.getTotalCount() > 0) {
                m.put("uncorrectedP99Ms", Histograms.ms(uncorrected.getValueAtPercentile(99)));
            }
            if (server.serviceTime().getTotalCount() > 0) {
                m.put("serviceP50Ms", Histograms.ms(server.serviceTime().getValueAtPercentile(50)));
                m.put("serviceP99Ms", Histograms.ms(server.serviceTime().getValueAtPercentile(99)));
                m.put("queueWaitP50Ms", Histograms.ms(server.queueWait().getValueAtPercentile(50)));
                m.put("queueWaitP99Ms", Histograms.ms(server.queueWait().getValueAtPercentile(99)));
            }
            double achieved = rec.achievedPerSecond();
            m.put("achievedRps", round(achieved, 1));
            m.put("offeredRps", rate);
            long errors = c.errors() + data.result().unfinished();
            m.put("errorPercent", c.offered() == 0 ? 0.0 : round(100.0 * errors / c.offered(), 3));
            m.put("timeouts", (double) c.timeouts());
            Double occupancy = null;
            Double bound = null;
            if (workers > 0 && server.measuredRequests() > 0) {
                occupancy = round(server.occupancyMs(), 3);
                bound = round(workers * 1_000.0 / server.occupancyMs(), 1);
                m.put("occupancyMs", occupancy);
                m.put("littleBoundRps", bound);
            }
            Histogram lag = rec.timerLag();
            if (lag.getTotalCount() > 0) {
                m.put("timerLagP99Ms", Histograms.ms(lag.getValueAtPercentile(99)));
            }
            if (Double.isFinite(data.cpuPercent())) {
                m.put("cpuUtilizationPercent", round(data.cpuPercent(), 1));
            }
            if (data.workerThreads() != null && Double.isFinite(data.blockedShare())) {
                m.put("workersBlockedPercent", round(100 * data.blockedShare(), 1));
                m.put("workersIdlePercent", round(100 * data.idleShare(), 1));
            }
            String note = errors == 0 ? null
                    : errors + " errors (" + c.timeouts() + " timeouts, " + c.httpErrors() + " non-200, "
                    + (c.failures() + data.result().unfinished()) + " failed)";
            result.trial(new Trial(index, m, null, note));

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("trial", index);
            d.put("scheduled", data.result().scheduled());
            d.put("offered", c.offered());
            d.put("sent", c.sent());
            d.put("ok", c.ok());
            d.put("httpErrors", c.httpErrors());
            d.put("failures", c.failures());
            d.put("timeouts", c.timeouts());
            d.put("unfinished", data.result().unfinished());
            d.put("achievedRps", round(achieved, 1));
            d.put("serverErrors", server.serverErrors());
            d.put("serverMeasuredRequests", server.measuredRequests());
            d.put("occupancyMs", occupancy);
            d.put("littleBoundRps", bound);
            d.put("client", Histograms.summary(client));
            d.put("uncorrected", Histograms.summary(uncorrected));
            d.put("service", Histograms.summary(server.serviceTime()));
            d.put("queueWait", Histograms.summary(server.queueWait()));
            d.put("timerLag", Histograms.summary(lag));
            d.put("measureStartMs", rec.measureStartOffsetNanos() / 1e6);
            d.put("measureEndMs", rec.measureEndOffsetNanos() / 1e6);
            d.put("series", rec.series());
            if (data.workerThreads() != null) {
                d.put("workerThreads", data.workerThreads());
            }
            trialDetails.add(d);
            ctx.progress(t + 1, totalTrials, "measured");
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("design", spec.variant());
        details.put("bindAddress", "127.0.0.1");
        details.put("workers", workers);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("rate", rate);
        cfg.put("warmupMs", warmupNanos / 1_000_000);
        cfg.put("durationMs", measureNanos / 1_000_000);
        cfg.put("poolSize", poolSize);
        cfg.put("downstreamMs", downstreamNanos / 1_000_000);
        cfg.put("cpuWorkUs", cpuWorkUs);
        cfg.put("cpuIterations", cpuIterations);
        cfg.put("iterationsPerMicro", round(itersPerMicro, 2));
        cfg.put("connections", connections);
        cfg.put("requestTimeoutMs", timeoutNanos / 1_000_000);
        cfg.put("windowMs", windowNanos / 1e6);
        details.put("config", cfg);
        details.put("trials", trialDetails);
        Map<String, Object> agg = new LinkedHashMap<>();
        agg.put("client", Histograms.summary(aggClient));
        agg.put("uncorrected", Histograms.summary(aggUncorrected));
        agg.put("service", Histograms.summary(aggService));
        agg.put("queueWait", Histograms.summary(aggQueue));
        Map<String, Object> spectrum = new LinkedHashMap<>();
        spectrum.put("client", Histograms.spectrum(aggClient));
        spectrum.put("uncorrected", Histograms.spectrum(aggUncorrected));
        spectrum.put("service", Histograms.spectrum(aggService));
        spectrum.put("queueWait", Histograms.spectrum(aggQueue));
        agg.put("spectrum", spectrum);
        details.put("aggregate", agg);
        details.put("histogram", Map.of("unit", "µs", "significantDigits", Histograms.SIGNIFICANT_DIGITS,
                "highestTrackableMs", Histograms.HIGHEST_MICROS / 1_000));
        result.detail("httpLoad", details);
        if (capture != null) {
            Map<String, Object> jvm = new LinkedHashMap<>();
            jvm.put("kind", "profile");
            jvm.put("jitMode", dev.forge.lab.scenarios.jvm.JvmLab.jitMode(DESCRIPTOR.variant(spec.variant()).orElseThrow()));
            jvm.put("jfr", dev.forge.lab.scenarios.jvm.JvmLab.stopAndSummarise(capture, ctx,
                    dev.forge.lab.jvm.JfrSummariser.Options.forPackage("dev.forge.lab.scenarios.performance")));
            result.detail("jvm", jvm);
        }

        observe(result, rate, workers, timeoutNanos / 1e6, totalOffered, totalErrors, totalTimeouts, aggClient, aggUncorrected, aggService, aggQueue);
        return result.build();
    }

    /**
     * @param workerThreads sampled worker states and top frames, or null when not profiled
     * @param blockedShare  share of worker samples inside a request but not running, NaN if not sampled
     * @param idleShare     share of worker samples idle in the pool, NaN if not sampled
     */
    private record TrialData(OpenLoopGenerator.Result result, TargetService.Stats server, int workers, double cpuPercent,
                             Map<String, Object> workerThreads, double blockedShare, double idleShare) {
    }

    private static TrialData runTrial(TargetService.Config config, OpenLoopGenerator.Plan plan, RunContext ctx, boolean profile) throws Exception {
        try (TargetService target = TargetService.start(config)) {
            AtomicInteger n = new AtomicInteger();
            ExecutorService clientPool = Executors.newCachedThreadPool(r -> {
                Thread th = new Thread(r, "load-client-" + n.incrementAndGet());
                th.setDaemon(true);
                return th;
            });
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .proxy(HttpClient.Builder.NO_PROXY)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofNanos(plan.timeoutNanos()))
                    .executor(clientPool)
                    .build();
            try {
                OpenLoopGenerator.Transport transport = (index, measured, timeoutNanos) -> client.sendAsync(
                        HttpRequest.newBuilder(target.uri())
                                .timeout(Duration.ofNanos(timeoutNanos))
                                .header(TargetService.MEASURED_HEADER, measured ? "1" : "0")
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.discarding()).thenApply(HttpResponse::statusCode);
                long start = System.nanoTime();
                boolean sampleThreads = profile && target.workers() > 0;   // virtual threads are invisible to ThreadMXBean
                OpenLoopGenerator.Result r;
                WorkerSampler sampler = new WorkerSampler(10_000_000L, start + plan.warmupNanos(), start + plan.warmupNanos() + plan.measureNanos(), sampleThreads);
                try {
                    r = OpenLoopGenerator.run(plan, NanoClock.SYSTEM, transport, ctx::checkCancelled);
                } finally {
                    sampler.close();
                }
                return new TrialData(r, target.stats(), target.workers(), sampler.cpuUtilizationPercent(),
                        sampleThreads ? sampler.summary() : null,
                        sampleThreads ? sampler.blockedShare() : Double.NaN, sampleThreads ? sampler.idleShare() : Double.NaN);
            } finally {
                client.shutdownNow();
                clientPool.shutdownNow();
            }
        }
    }

    private static void observe(ScenarioResult.Builder result, double rate, int workers, double timeoutMs, long offered, long errors, long timeouts,
                                Histogram client, Histogram uncorrected, Histogram service, Histogram queue) {
        List<Trial> trials = result.trials();
        if (trials.isEmpty()) {
            return;
        }
        double[] achieved = values(trials, "achievedRps");
        result.observation(String.format("Offered %.0f req/s; achieved a median of %.1f req/s across %d trials (range %.1f–%.1f).",
                rate, Summary.median(achieved), trials.size(), achieved[0], achieved[achieved.length - 1]));

        double[] bounds = values(trials, "littleBoundRps");
        double[] occupancy = values(trials, "occupancyMs");
        if (workers > 0 && bounds.length > 0) {
            double bound = Summary.median(bounds);
            result.observation(String.format(
                    "Little's law: %d workers ÷ %.2f ms of worker time per request (median) ⇒ at most %.0f req/s. The offered rate was %.0f%% of that bound.",
                    workers, Summary.median(occupancy), bound, 100.0 * rate / bound));
        } else if (workers == 0) {
            result.observation("Virtual threads: no fixed pool, so no Little's-law bound from pool size; concurrency was limited by the client's connection limit.");
        }

        double[] p99 = values(trials, "latencyP99Ms");
        double[] p50 = values(trials, "latencyP50Ms");
        if (p99.length > 0) {
            result.observation(String.format("Client latency from the intended send time: median of per-trial p50 %.2f ms, p99 %.2f ms (p99 range %.2f–%.2f ms).",
                    Summary.median(p50), Summary.median(p99), p99[0], p99[p99.length - 1]));
            long capped = Arrays.stream(p99).filter(v -> v >= timeoutMs).count();
            if (capped > 0) {
                result.observation(String.format(
                        "In %d of %d trials p99 reached the %.0f ms request timeout: at least 1%% of requests timed out, and timeouts are recorded when the client gave up, so p99 and higher percentiles there are lower bounds — the real wait was longer.",
                        capped, p99.length, timeoutMs));
            }
        }
        if (client.getTotalCount() > 0 && uncorrected.getTotalCount() > 0) {
            double corrected99 = Histograms.ms(client.getValueAtPercentile(99));
            double naive99 = Histograms.ms(uncorrected.getValueAtPercentile(99));
            if (naive99 > 0 && corrected99 / naive99 >= 1.5) {
                result.observation(String.format(
                        "Across all trials, p99 measured from the actual send time is %.2f ms but %.2f ms from the intended send time (×%.1f): requests waited in the generator for a free connection, and a generator that ignores that wait would under-report the tail.",
                        naive99, corrected99, corrected99 / naive99));
            } else {
                result.observation(String.format(
                        "Across all trials, p99 from the actual send time (%.2f ms) and from the intended send time (%.2f ms) are close: the generator was rarely held back waiting for a connection.",
                        naive99, corrected99));
            }
        }
        if (queue.getTotalCount() > 0) {
            result.observation(String.format("Server queue wait across all trials: p50 %.2f ms, p99 %.2f ms, max %.2f ms.",
                    Histograms.ms(queue.getValueAtPercentile(50)), Histograms.ms(queue.getValueAtPercentile(99)), Histograms.ms(queue.getMaxValue())));
        }
        result.observation(errors == 0
                ? "No errors among " + offered + " measured requests."
                : String.format("%d of %d measured requests (%.2f%%) were errors, %d of them timeouts.", errors, offered, 100.0 * errors / offered, timeouts));
        if (errors == 0 && client.getTotalCount() > 0 && queue.getTotalCount() > 0) {
            double clientMean = client.getMean() / 1_000.0;
            double queueMean = queue.getMean() / 1_000.0;
            double serviceMean = service.getMean() / 1_000.0;
            result.observation(String.format(
                    "Means add up where percentiles do not: mean client latency %.2f ms = server queue wait %.2f ms + service time %.2f ms + %.2f ms outside the server's measurement (generator timer lag and connection wait, loopback I/O in both directions, client callbacks).",
                    clientMean, queueMean, serviceMean, clientMean - queueMean - serviceMean));
        }
        double[] lag = values(trials, "timerLagP99Ms");
        if (lag.length > 0) {
            result.observation(String.format(
                    "The generator's timer woke up late by up to %.2f ms at p99 (median across trials); that lateness is part of client latency.", Summary.median(lag)));
        }
        double[] cpu = values(trials, "cpuUtilizationPercent");
        double[] blocked = values(trials, "workersBlockedPercent");
        double[] idle = values(trials, "workersIdlePercent");
        if (cpu.length > 0) {
            result.observation(String.format("The runner process used %.1f%% of its CPU capacity during the sustained phase (median across trials; client, server and GC together).",
                    Summary.median(cpu)));
        }
        if (blocked.length > 0) {
            result.observation(String.format("Worker-thread samples (wall clock, median across trials): %.1f%% inside a request but not running, %.1f%% idle waiting for a request, the rest running. The trial details list the frames.",
                    Summary.median(blocked), Summary.median(idle)));
        }
        if (client.getTotalCount() < 1_000) {
            result.observation("Fewer than 1 000 measured requests across trials: p99.9 is not resolvable and equals the maximum.");
        }
    }

    private static void putPercentiles(Map<String, Double> m, String prefix, Histogram h, boolean withMax) {
        if (h.getTotalCount() == 0) {
            return;
        }
        m.put(prefix + "P50Ms", Histograms.ms(h.getValueAtPercentile(50)));
        m.put(prefix + "P90Ms", Histograms.ms(h.getValueAtPercentile(90)));
        m.put(prefix + "P95Ms", Histograms.ms(h.getValueAtPercentile(95)));
        m.put(prefix + "P99Ms", Histograms.ms(h.getValueAtPercentile(99)));
        if (withMax) {
            m.put(prefix + "MaxMs", Histograms.ms(h.getMaxValue()));
        }
    }

    /** About 80 windows per trial timeline, at least 50 ms, rounded up to 10 ms. */
    static long windowNanos(long spanNanos) {
        long ms = Math.max(50, (long) Math.ceil(spanNanos / 1e6 / 80));
        ms = (ms + 9) / 10 * 10;
        return ms * 1_000_000L;
    }

    private static double[] values(List<Trial> trials, String metric) {
        return trials.stream().map(t -> t.metrics().get(metric)).filter(v -> v != null && Double.isFinite(v))
                .mapToDouble(Double::doubleValue).sorted().toArray();
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
