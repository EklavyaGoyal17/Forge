package dev.forge.lab.runner;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.forge.lab.scenario.ScenarioCatalog;
import dev.forge.lab.scenario.VariantSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * The runner agent: a small HTTP service in the dedicated runner container that launches runner
 * JVMs on behalf of the server. It exists so that runs execute in a container that can reach
 * neither the platform database nor the internet (docs/SECURITY.md).
 *
 * <pre>
 * POST /runs                 {runId, heapMb, timeoutSeconds, spec}  → 200 application/x-ndjson:
 *                            every runner stdout line, then {"agent":"outcome", ...}
 * POST /runs/{runId}/cancel  → 204 (404 if not running)
 * GET  /health               → 200 ok (no token; used by the container health check)
 * </pre>
 *
 * The agent trusts as little as possible from the request: a shared token is required; heap and
 * timeout are bounded by the agent's own limits; JVM variant flags are derived from the agent's own
 * scenario catalog (never from the request) and vetted against the allow-list; the run spec is
 * passed to the runner on stdin, which validates it again. If the server disconnects, the run is
 * cancelled.
 */
public final class RunnerAgent {

    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final int MAX_BODY = 1 << 20;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Config(String bindAddress, int port, String token, ChildJvm.Launch launch, Path workRoot, int maxHeapMb,
                         int maxTimeoutSeconds, int maxConcurrentRuns, boolean keepWorkDirs, String forgeVersion) {
        public Config {
            if (token == null || token.length() < 16) {
                throw new IllegalArgumentException("the runner agent needs a token of at least 16 characters (FORGE_RUNNER_AGENT_TOKEN)");
            }
        }
    }

    private final Config config;
    private final Semaphore slots;
    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();
    private final Map<String, Object> host;
    private HttpServer server;

    public RunnerAgent(Config config) {
        this.config = config;
        this.slots = new Semaphore(config.maxConcurrentRuns());
        this.host = HostFacts.collect(config.forgeVersion());
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> env = System.getenv();
        ChildJvm.Launch launch = new ChildJvm.Launch(
                env.getOrDefault("FORGE_RUNNER_JAVA", ChildJvm.defaultJavaExecutable()),
                env.getOrDefault("FORGE_RUNNER_CLASSPATH", System.getProperty("java.class.path")),
                RunnerMain.class.getName(),
                Integer.parseInt(env.getOrDefault("FORGE_RUNNER_ACTIVE_PROCESSOR_COUNT", "0")),
                Long.parseLong(env.getOrDefault("FORGE_RUNNER_STDOUT_CAP_BYTES", "16777216")),
                Integer.parseInt(env.getOrDefault("FORGE_RUNNER_STDERR_CAP_BYTES", "262144")));
        Config c = new Config(env.getOrDefault("FORGE_RUNNER_AGENT_BIND", "0.0.0.0"),
                Integer.parseInt(env.getOrDefault("FORGE_RUNNER_AGENT_PORT", "8095")),
                env.get("FORGE_RUNNER_AGENT_TOKEN"), launch,
                Path.of(env.getOrDefault("FORGE_RUNNER_WORK_ROOT", System.getProperty("java.io.tmpdir") + "/forge-runs")),
                Integer.parseInt(env.getOrDefault("FORGE_RUNNER_MAX_HEAP_MB", "1024")),
                Integer.parseInt(env.getOrDefault("FORGE_RUNNER_MAX_TIMEOUT_SECONDS", "600")),
                Integer.parseInt(env.getOrDefault("FORGE_RUNNER_MAX_CONCURRENT_RUNS", "1")),
                Boolean.parseBoolean(env.getOrDefault("FORGE_RUNNER_KEEP_WORK_DIRS", "false")),
                env.getOrDefault("FORGE_VERSION", "dev"));
        RunnerAgent agent = new RunnerAgent(c);
        agent.start();
        System.err.println("forge runner agent listening on " + c.bindAddress() + ":" + agent.port());
    }

    public void start() throws IOException {
        Files.createDirectories(config.workRoot());
        server = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port()), 64);
        server.createContext("/health", ex -> {
            try (ex) {
                respond(ex, 200, "ok");
            }
        });
        server.createContext("/runs", this::runs);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agent-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        running.values().forEach(f -> f.set(true));
        server.stop(1);
    }

    private void runs(HttpExchange ex) throws IOException {
        try (ex) {
            if (!authorized(ex)) {
                respond(ex, 401, "missing or wrong runner token");
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (ex.getRequestMethod().equals("POST") && path.equals("/runs")) {
                launch(ex);
            } else if (ex.getRequestMethod().equals("POST") && path.startsWith("/runs/") && path.endsWith("/cancel")) {
                AtomicBoolean flag = running.get(path.substring("/runs/".length(), path.length() - "/cancel".length()));
                if (flag == null) {
                    respond(ex, 404, "not running");
                } else {
                    flag.set(true);
                    respond(ex, 204, null);
                }
            } else {
                respond(ex, 404, "not found");
            }
        }
    }

    private boolean authorized(HttpExchange ex) {
        String given = ex.getRequestHeaders().getFirst("X-Forge-Runner-Token");
        return given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), config.token().getBytes(StandardCharsets.UTF_8));
    }

    private void launch(HttpExchange ex) throws IOException {
        JsonNode req;
        try (InputStream in = ex.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                respond(ex, 413, "request too large");
                return;
            }
            req = JSON.readTree(body);
        } catch (RuntimeException e) {
            respond(ex, 400, "not JSON");
            return;
        }
        String runId = req.path("runId").asString("");
        int heapMb = req.path("heapMb").asInt(-1);
        int timeoutSeconds = req.path("timeoutSeconds").asInt(-1);
        JsonNode spec = req.path("spec");
        if (!RUN_ID.matcher(runId).matches() || heapMb < 32 || heapMb > config.maxHeapMb()
                || timeoutSeconds < 1 || timeoutSeconds > config.maxTimeoutSeconds() || !spec.isObject()) {
            respond(ex, 400, "invalid run request (runId, heapMb ≤ " + config.maxHeapMb() + ", timeoutSeconds ≤ "
                    + config.maxTimeoutSeconds() + ", spec)");
            return;
        }
        List<String> flags;
        try {
            flags = ChildJvm.vetVariantFlags(ScenarioCatalog.find(spec.path("scenarioId").asString(""))
                    .flatMap(s -> s.descriptor().variant(spec.path("variant").asString("")))
                    .map(VariantSpec::jvmFlags).orElse(List.of()));
        } catch (IllegalStateException e) {
            respond(ex, 400, e.getMessage());
            return;
        }
        if (!slots.tryAcquire()) {
            respond(ex, 429, "the runner is busy (" + config.maxConcurrentRuns() + " concurrent runs)");
            return;
        }
        AtomicBoolean cancel = new AtomicBoolean();
        if (running.putIfAbsent(runId, cancel) != null) {
            slots.release();
            respond(ex, 409, "run " + runId + " is already running");
            return;
        }
        Path workDir = Files.createDirectories(config.workRoot().resolve("run-" + runId));
        try {
            ex.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            ex.sendResponseHeaders(200, 0);   // chunked: lines are streamed as the runner writes them
            OutputStream out = ex.getResponseBody();
            Object lock = new Object();
            ChildJvm.Outcome o = ChildJvm.run(config.launch(), runId, heapMb, timeoutSeconds, flags, workDir,
                    JSON.writeValueAsBytes(spec), line -> {
                        synchronized (lock) {
                            try {
                                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                                out.flush();
                            } catch (IOException e) {
                                cancel.set(true);   // the server went away: stop the run
                            }
                        }
                    }, cancel::get);
            Map<String, Object> outcome = new LinkedHashMap<>();
            outcome.put("agent", "outcome");
            outcome.put("termination", o.termination().name());
            outcome.put("exitCode", o.exitCode());
            outcome.put("stderr", o.stderr());
            outcome.put("stderrTruncated", o.stderrTruncated());
            outcome.put("commandLine", o.commandLine());
            outcome.put("host", host);
            synchronized (lock) {
                out.write((JSON.writeValueAsString(outcome) + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running.remove(runId);
            slots.release();
            if (!config.keepWorkDirs()) {
                ChildJvm.deleteRecursively(workDir);
            }
        }
    }

    private static void respond(HttpExchange ex, int status, String text) throws IOException {
        if (text == null) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }
}
