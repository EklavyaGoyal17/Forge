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
import org.HdrHistogram.Histogram;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Cross-checks FORGE's own load generator against Grafana k6 on the same embedded target.
 *
 * <p>Both drive the target at a constant arrival rate (open model) with the same concurrency limit.
 * They differ in what happens when no connection / virtual user is free: FORGE waits and measures
 * latency from the request's <em>intended</em> send time; k6's {@code constant-arrival-rate}
 * executor <em>drops</em> the iteration and reports it in {@code dropped_iterations}, and measures
 * {@code http_req_duration} from the moment the request is actually sent. The server-side service
 * time is measured by the target in both cases — the same instrument — and anchors the comparison.
 *
 * <p>k6 runs as a child process of the runner (the static binary is part of the FORGE Docker
 * image), against the loopback target this trial started; nothing else can be addressed, because
 * the script is generated from validated integers and the target's own URI.
 */
public final class GeneratorCrossCheckScenario implements Scenario {

    public static final String ID = "performance.generator-crosscheck";
    private static final String SRC = GeneratorCrossCheckScenario.class.getName() + "#";
    private static final List<String> K6_LOCATIONS = List.of("/usr/bin/k6", "/usr/local/bin/k6");
    private static final Map<String, Design> DESIGNS = Map.of(
            "blocking-pool", Design.BLOCKING_POOL, "large-pool", Design.LARGE_POOL, "async", Design.ASYNC, "virtual-threads", Design.VIRTUAL_THREADS);
    private static final long TRIAL_OVERHEAD_NANOS = 10_000_000_000L;   // includes starting k6

    private static final ScenarioDescriptor DESCRIPTOR = new ScenarioDescriptor(
            ID, 1, Lab.PERFORMANCE,
            "Load generator cross-check: FORGE vs k6",
            "Drive the same embedded HTTP service at the same constant arrival rate with FORGE's generator and with Grafana k6, and compare what each reports — including what each does when the service falls behind.",
            "Do two open-model load generators agree on the same service, and where they disagree, is it the service or the definition of latency?",
            null,
            List.of(
                    ParameterSpec.choice("design", "Server design", "async", List.of("blocking-pool", "large-pool", "async", "virtual-threads"),
                            "The target service's design (see the thread-pool starvation scenario). blocking-pool at 300 req/s is overloaded; async is not."),
                    ParameterSpec.integer("rate", "Offered rate", 300, 10, 2_000, "req/s", "Constant arrival rate for both generators."),
                    ParameterSpec.integer("warmupMs", "Warm-up phase", 1_000, 0, 5_000, "ms", "Load before measurement; not measured. For k6: 0 or at least 1000 ms (its executor's minimum stage length)."),
                    ParameterSpec.integer("durationMs", "Sustained phase", 3_000, 1_000, 10_000, "ms", "Measured phase of each trial."),
                    ParameterSpec.integer("poolSize", "Worker pool size", 4, 1, 32, "threads", "Server worker threads."),
                    ParameterSpec.integer("downstreamMs", "Downstream latency", 20, 0, 200, "ms", "Simulated downstream call per request."),
                    ParameterSpec.integer("cpuWorkUs", "CPU work per request", 200, 0, 1_000, "µs", "Calibrated CPU work per request."),
                    ParameterSpec.integer("connections", "Max in flight", 64, 1, 256, "requests",
                            "FORGE: connection limit (excess waits). k6: preAllocatedVUs = maxVUs (excess iterations are dropped)."),
                    ParameterSpec.integer("requestTimeoutMs", "Request timeout", 2_000, 100, 5_000, "ms", "Per request, for both generators.")),
            List.of(
                    new VariantSpec("forge", "FORGE open-loop generator",
                            "JDK HttpClient driven on a fixed schedule; latency from the intended send time; requests wait for a free connection.", null, SRC + "forge"),
                    new VariantSpec("k6", "Grafana k6 (constant-arrival-rate)",
                            "k6 v1.0.0 as a child process; latency from the actual send; iterations without a free VU are dropped. Requires the k6 binary, which the FORGE Docker image includes.", null, SRC + "k6")),
            List.of(
                    new MetricSpec("p99Ms", "p99 latency (tool's definition)", "ms", Direction.LOWER_IS_BETTER,
                            "FORGE: from the intended send time. k6: http_req_duration, from the actual send. Not the same quantity under overload."),
                    new MetricSpec("p50Ms", "p50 latency (tool's definition)", "ms", Direction.LOWER_IS_BETTER, "As p99, at the median."),
                    new MetricSpec("p99FromSendMs", "p99 from actual send", "ms", Direction.LOWER_IS_BETTER,
                            "Comparable across tools: FORGE's latency from the actual send, k6's http_req_duration."),
                    new MetricSpec("maxMs", "Max latency (tool's definition)", "ms", Direction.LOWER_IS_BETTER, "Largest latency the tool reported."),
                    new MetricSpec("achievedRps", "Achieved throughput", "req/s", Direction.HIGHER_IS_BETTER, "Successful responses ÷ sustained-phase duration."),
                    new MetricSpec("errorPercent", "Error rate", "%", Direction.LOWER_IS_BETTER, "Sent requests that failed or timed out."),
                    new MetricSpec("droppedPercent", "Not sent", "%", Direction.LOWER_IS_BETTER,
                            "Scheduled requests that were never sent: k6 dropped iterations; FORGE requests that found no free connection before their deadline."),
                    new MetricSpec("serviceP50Ms", "Server service time p50", "ms", Direction.LOWER_IS_BETTER, "Measured by the target, identically for both tools."),
                    new MetricSpec("serviceP99Ms", "Server service time p99", "ms", Direction.LOWER_IS_BETTER, "Measured by the target, identically for both tools."),
                    new MetricSpec("serverRequests", "Requests served", "requests", Direction.NEUTRAL, "Measured requests the target served.")),
            "p99Ms",
            new ScenarioDescriptor.TrialPolicy(3, 10, 0, 2),
            300,
            """
            A load test is a measurement instrument, and instruments need calibrating. This scenario runs the same \
            embedded service under the same open-model load with two different generators: FORGE's own, and Grafana k6, \
            a widely used open-source tool.

            Both keep the arrival rate fixed whether or not the service keeps up, which avoids the worst form of \
            coordinated omission. They disagree, by design, on what to do when every connection is busy. FORGE lets the \
            request wait and counts that wait as latency, because the user arriving at that moment would have waited. \
            k6's `constant-arrival-rate` executor skips the iteration and counts it as **dropped**; its \
            `http_req_duration` starts when the request is actually sent.

            So on a healthy service the two should agree closely. On an overloaded one, FORGE's p99 grows towards the \
            timeout while k6's stays near the service time and its dropped count grows instead. Neither is wrong; they \
            answer different questions. The server's own service time is measured identically in both runs, which is \
            what tells you whether the *service* behaved the same.""",
            List.of(
                    "Both generators share the machine with the target; k6 runs as a separate process, FORGE's generator inside the runner JVM. Generator overhead differs and is part of each measurement.",
                    "k6 is used only against the service this trial starts on 127.0.0.1. The script is generated from validated parameters; nothing in it comes from user text.",
                    "k6 uses its own HTTP stack, connection reuse and timers; small differences in latency on a healthy service are expected.",
                    "k6 is only available where its binary is installed (the FORGE Docker image ships k6 v1.0.0). Elsewhere the k6 variant fails with a clear message rather than producing numbers.",
                    "k6 starts a local REST API on 127.0.0.1:6565 while it runs; with concurrent runs enabled two k6 processes would conflict (FORGE runs one run at a time by default)."));

    @Override
    public ScenarioDescriptor descriptor() {
        return DESCRIPTOR;
    }

    record Plan(Design design, double rate, long warmupNanos, long measureNanos, int poolSize, long downstreamNanos, long cpuWorkUs,
                int connections, long timeoutNanos) {
    }

    @Override
    public ScenarioResult run(RunSpec spec, RunContext ctx) throws Exception {
        Plan p = new Plan(DESIGNS.get(spec.stringParam("design")), spec.longParam("rate"), spec.longParam("warmupMs") * 1_000_000L,
                spec.longParam("durationMs") * 1_000_000L, spec.intParam("poolSize"), spec.longParam("downstreamMs") * 1_000_000L,
                spec.longParam("cpuWorkUs"), spec.intParam("connections"), spec.longParam("requestTimeoutMs") * 1_000_000L);
        boolean k6 = spec.variant().equals("k6");
        Optional<Path> k6Binary = k6Binary();
        if (k6 && p.warmupNanos() > 0 && p.warmupNanos() < 1_000_000_000L) {
            throw new IllegalArgumentException("k6's constant-arrival-rate executor needs each stage to last at least 1 s: use a warm-up of 0 or ≥ 1000 ms");
        }
        if (k6 && k6Binary.isEmpty()) {
            throw new IllegalStateException("k6 is not installed in this runner (looked for " + K6_LOCATIONS
                    + "); the FORGE Docker image includes it. The forge variant works everywhere.");
        }
        int total = spec.warmupTrials() + spec.trials();
        long perTrial = p.warmupNanos() + p.measureNanos() + p.timeoutNanos() + TRIAL_OVERHEAD_NANOS;
        if ((double) perTrial * total > ctx.deadlineNanos() - System.nanoTime()) {
            throw new IllegalArgumentException("the trials do not fit in the run timeout; increase it or reduce trials/durations");
        }
        double itersPerMicro = CpuWork.calibrateIterationsPerMicro();
        TargetService.Config config = new TargetService.Config(p.design(), p.poolSize(), Math.round(p.cpuWorkUs() * itersPerMicro), p.downstreamNanos());
        ctx.info(String.format(Locale.ROOT, "%s generator, %.0f req/s against the %s design", spec.variant(), p.rate(), spec.stringParam("design")));
        if (k6) {
            ctx.info("k6 binary: " + k6Binary.get() + " (" + k6Version(k6Binary.get(), ctx.workDir()) + ")");
        }

        ScenarioResult.Builder result = ScenarioResult.builder();
        List<Map<String, Object>> trials = new ArrayList<>();
        for (int t = 0; t < total; t++) {
            ctx.checkCancelled();
            Map<String, Object> details = new LinkedHashMap<>();
            Map<String, Double> m;
            try (TargetService target = TargetService.start(config)) {
                m = k6 ? k6Trial(k6Binary.get(), p, target, ctx, t, details) : forgeTrial(p, target, ctx);
                TargetService.Stats s = target.stats();
                if (s.serviceTime().getTotalCount() > 0) {
                    m.put("serviceP50Ms", Histograms.ms(s.serviceTime().getValueAtPercentile(50)));
                    m.put("serviceP99Ms", Histograms.ms(s.serviceTime().getValueAtPercentile(99)));
                }
                m.put("serverRequests", (double) s.measuredRequests());
                details.put("service", Histograms.summary(s.serviceTime()));
            }
            ctx.progress(t + 1, total, t < spec.warmupTrials() ? "warm-up" : "measured");
            if (t < spec.warmupTrials()) {
                continue;
            }
            details.put("trial", t - spec.warmupTrials());
            trials.add(details);
            result.trial(new Trial(t - spec.warmupTrials(), m, null, null));
        }
        result.detail("crossCheck", Map.of("generator", spec.variant(), "design", spec.stringParam("design"), "trials", trials,
                "k6", k6 ? Map.of("binary", k6Binary.get().toString(), "executor", "constant-arrival-rate") : Map.of()));
        observe(result, spec.variant(), p);
        return result.build();
    }

    // forge:snippet forge
    private static Map<String, Double> forgeTrial(Plan p, TargetService target, RunContext ctx) throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool(r -> {
            Thread th = new Thread(r, "crosscheck-client");
            th.setDaemon(true);
            return th;
        });
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).proxy(HttpClient.Builder.NO_PROXY)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofNanos(p.timeoutNanos())).executor(pool).build();
        try {
            OpenLoopGenerator.Plan plan = new OpenLoopGenerator.Plan(p.rate(), p.warmupNanos(), p.measureNanos(), p.timeoutNanos(),
                    p.connections(), ThreadPoolStarvationScenario.windowNanos(p.warmupNanos() + p.measureNanos() + p.timeoutNanos()));
            OpenLoopGenerator.Result r = OpenLoopGenerator.run(plan, NanoClock.SYSTEM, (i, measured, timeout) -> client.sendAsync(
                    HttpRequest.newBuilder(target.uri()).timeout(Duration.ofNanos(timeout))
                            .header(TargetService.MEASURED_HEADER, measured ? "1" : "0").GET().build(),
                    HttpResponse.BodyHandlers.discarding()).thenApply(HttpResponse::statusCode), ctx::checkCancelled);
            LatencyRecorder rec = r.recorder();
            LatencyRecorder.Counts c = rec.counts();
            Histogram corrected = rec.corrected();
            Histogram fromSend = rec.uncorrected();
            Map<String, Double> m = new LinkedHashMap<>();
            if (corrected.getTotalCount() > 0) {
                m.put("p50Ms", Histograms.ms(corrected.getValueAtPercentile(50)));
                m.put("p99Ms", Histograms.ms(corrected.getValueAtPercentile(99)));
                m.put("maxMs", Histograms.ms(corrected.getMaxValue()));
            }
            if (fromSend.getTotalCount() > 0) {
                m.put("p99FromSendMs", Histograms.ms(fromSend.getValueAtPercentile(99)));
            }
            m.put("achievedRps", round(rec.achievedPerSecond(), 1));
            long sentFailures = c.httpErrors() + c.failures() + (c.timeouts() - (c.offered() - c.sent()));
            m.put("errorPercent", c.sent() == 0 ? 0.0 : round(100.0 * Math.max(0, sentFailures) / c.sent(), 3));
            m.put("droppedPercent", c.offered() == 0 ? 0.0 : round(100.0 * (c.offered() - c.sent()) / c.offered(), 3));
            return m;
        } finally {
            client.shutdownNow();
            pool.shutdownNow();
        }
    }
    // forge:end

    // forge:snippet k6
    static String k6Script(Plan p, String url) {
        long warmMs = p.warmupNanos() / 1_000_000;
        long measureMs = p.measureNanos() / 1_000_000;
        long timeoutMs = p.timeoutNanos() / 1_000_000;
        String executor = "executor: 'constant-arrival-rate', rate: %d, timeUnit: '1s', preAllocatedVUs: %d, maxVUs: %d, gracefulStop: '%ds'"
                .formatted((long) p.rate(), p.connections(), p.connections(), timeoutMs / 1000 + 1);
        StringBuilder scenarios = new StringBuilder();
        if (warmMs > 0) {
            scenarios.append("    warmup: { ").append(executor).append(", duration: '").append(warmMs).append("ms', exec: 'warm' },\n");
        }
        scenarios.append("    measure: { ").append(executor).append(", duration: '").append(measureMs)
                .append("ms', startTime: '").append(warmMs).append("ms', exec: 'measured' },\n");
        return """
                import http from 'k6/http';
                export const options = {
                  discardResponseBodies: true,
                  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
                  scenarios: {
                %s  },
                  // always-true thresholds make k6 report these sub-metrics for the measured phase alone
                  thresholds: {
                    'http_req_duration{scenario:measure}': ['max>=0'],
                    'http_req_failed{scenario:measure}': ['rate>=0'],
                    'http_reqs{scenario:measure}': ['count>=0'],
                    'dropped_iterations{scenario:measure}': ['count>=0'],
                  },
                };
                const url = '%s';
                const params = (measured) => ({ timeout: '%dms', headers: { '%s': measured ? '1' : '0' } });
                export function warm() { http.get(url, params(false)); }
                export function measured() { http.get(url, params(true)); }
                export function handleSummary(data) { return { 'summary.json': JSON.stringify(data) }; }
                """.formatted(scenarios, url, timeoutMs, TargetService.MEASURED_HEADER);
    }
    // forge:end

    private static Map<String, Double> k6Trial(Path k6, Plan p, TargetService target, RunContext ctx, int trial, Map<String, Object> details)
            throws Exception {
        String url = target.uri().toString();
        if (!url.startsWith("http://127.0.0.1:")) {
            throw new IllegalStateException("refusing to point k6 at " + url);
        }
        Path dir = Files.createDirectories(ctx.workDir().resolve("k6-" + trial));
        Path script = dir.resolve("load.js");
        Files.writeString(script, k6Script(p, url), StandardCharsets.UTF_8);
        ProcessBuilder pb = new ProcessBuilder(k6.toString(), "run", "--quiet", "--no-color", "--no-usage-report", script.toString())
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("k6.log").toFile());
        pb.environment().clear();
        pb.environment().put("HOME", dir.toString());
        pb.environment().put("K6_NO_USAGE_REPORT", "true");
        Process proc = pb.start();
        long limit = p.warmupNanos() + p.measureNanos() + p.timeoutNanos() + TRIAL_OVERHEAD_NANOS;
        boolean done;
        try {
            done = proc.waitFor(limit, TimeUnit.NANOSECONDS);
        } finally {
            proc.descendants().forEach(ProcessHandle::destroyForcibly);
            proc.destroyForcibly();
        }
        String log = Files.exists(dir.resolve("k6.log")) ? Files.readString(dir.resolve("k6.log")) : "";
        if (!done) {
            throw new IllegalStateException("k6 did not finish within " + limit / 1_000_000_000 + " s");
        }
        if (proc.exitValue() != 0) {
            throw new IllegalStateException("k6 exited with " + proc.exitValue() + ": " + tail(log));
        }
        if (!log.isBlank()) {
            ctx.artifact("k6-trial-" + trial + ".log", "text/plain", tail(log));
        }
        Path summary = dir.resolve("summary.json");
        if (!Files.exists(summary)) {
            throw new IllegalStateException("k6 wrote no summary: " + tail(log));
        }
        return parseK6Summary(Files.readString(summary), p, details);
    }

    /** Reads the measured-phase sub-metrics from a k6 {@code handleSummary} JSON document. */
    static Map<String, Double> parseK6Summary(String json, Plan p, Map<String, Object> details) throws IOException {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var metrics = mapper.readTree(json).path("metrics");
        var duration = metrics.path("http_req_duration{scenario:measure}").path("values");
        var reqs = metrics.path("http_reqs{scenario:measure}").path("values");
        var failed = metrics.path("http_req_failed{scenario:measure}").path("values");
        var dropped = metrics.path("dropped_iterations{scenario:measure}").path("values");
        double requests = reqs.path("count").asDouble(0);
        double failRate = failed.path("rate").asDouble(0);
        double droppedCount = dropped.path("count").asDouble(0);
        double scheduled = p.rate() * p.measureNanos() / 1e9;
        Map<String, Double> m = new LinkedHashMap<>();
        if (duration.isObject() && requests > 0) {
            m.put("p50Ms", round(duration.path("med").asDouble(), 3));
            m.put("p99Ms", round(duration.path("p(99)").asDouble(), 3));
            m.put("p99FromSendMs", round(duration.path("p(99)").asDouble(), 3));
            m.put("maxMs", round(duration.path("max").asDouble(), 3));
        }
        m.put("achievedRps", round(requests * (1 - failRate) / (p.measureNanos() / 1e9), 1));
        m.put("errorPercent", round(100 * failRate, 3));
        m.put("droppedPercent", scheduled == 0 ? 0.0 : round(100 * droppedCount / scheduled, 3));
        details.put("k6", Map.of("requests", requests, "failedRate", failRate, "droppedIterations", droppedCount,
                "duration", mapper.convertValue(duration, Map.class)));
        return m;
    }

    static Optional<Path> k6Binary() {
        String override = System.getProperty("forge.k6.path");
        List<String> candidates = override == null ? K6_LOCATIONS : List.of(override);
        return candidates.stream().map(Path::of).filter(Files::isExecutable).findFirst();
    }

    private static String k6Version(Path k6, Path workDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder(k6.toString(), "version").redirectErrorStream(true).directory(workDir.toFile());
            pb.environment().clear();
            pb.environment().put("HOME", workDir.toString());
            Process proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            proc.waitFor(10, TimeUnit.SECONDS);
            return out.isEmpty() ? "unknown version" : out.lines().findFirst().orElse(out);
        } catch (IOException | InterruptedException e) {
            return "version unknown: " + e.getMessage();
        }
    }

    private static void observe(ScenarioResult.Builder result, String generator, Plan p) {
        List<Trial> trials = result.trials();
        if (trials.isEmpty()) {
            return;
        }
        result.observation(String.format(Locale.ROOT, "%s: median across %d trials — p99 %.2f ms (%s), p99 from actual send %.2f ms, achieved %.1f of %.0f req/s, %.2f%% not sent, %.2f%% errors.",
                generator, trials.size(), median(trials, "p99Ms"), generator.equals("k6") ? "from actual send" : "from intended send",
                median(trials, "p99FromSendMs"), median(trials, "achievedRps"), p.rate(), median(trials, "droppedPercent"), median(trials, "errorPercent")));
        result.observation(String.format(Locale.ROOT, "Server-side service time (same instrument for both generators): p50 %.2f ms, p99 %.2f ms (medians across trials).",
                median(trials, "serviceP50Ms"), median(trials, "serviceP99Ms")));
        if (median(trials, "droppedPercent") > 1) {
            result.observation(generator.equals("k6")
                    ? "k6 dropped iterations: it could not start them on schedule because every VU was busy. Those users are missing from http_req_duration, not counted as slow."
                    : "Some requests never got a connection before their deadline; FORGE counts them as timeouts at the deadline.");
        }
    }

    private static double median(List<Trial> trials, String metric) {
        double[] v = trials.stream().map(t -> t.metrics().get(metric)).filter(x -> x != null && Double.isFinite(x))
                .mapToDouble(Double::doubleValue).sorted().toArray();
        return v.length == 0 ? Double.NaN : (v[(v.length - 1) / 2] + v[v.length / 2]) / 2;
    }

    private static String tail(String s) {
        return s.length() <= 4_000 ? s : s.substring(s.length() - 4_000);
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

}
