package dev.forge.lab.scenarios.failure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.forge.lab.performance.LoopbackHttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * The downstream dependency: an "inventory" HTTP service started inside the runner JVM, bound to
 * 127.0.0.1 on an ephemeral port. It is the only thing faults are injected into — FORGE never
 * injects faults into anything outside the runner process.
 *
 * <p>A fixed pool of {@code workers} threads serves {@code GET /stock}; each request takes
 * {@code baseLatency}. Like a real server, it keeps working on a request after the client has
 * given up: it only notices when writing the response fails, which is counted as wasted work.
 *
 * <p>Faults, switched on and off by the scenario at planned times:
 * <ul>
 *   <li>{@code LATENCY} — every request takes {@code baseLatency + faultLatency};</li>
 *   <li>{@code ERRORS} — a seeded fraction of requests answers HTTP 500 after the base latency;</li>
 *   <li>{@code DROP} — requests are accepted and the connection is closed without a response;</li>
 *   <li>{@code REFUSE} — the listener is closed (connection refused), then a fresh instance starts on
 *       a new port, which clients discover through {@link #address()} — a restart with service
 *       discovery.</li>
 * </ul>
 */
final class InventoryService implements AutoCloseable {

    enum Fault { NONE, LATENCY, ERRORS, DROP, REFUSE }

    private static final byte[] BODY = "{\"sku\":\"forge-1\",\"stock\":42}".getBytes(StandardCharsets.US_ASCII);

    private final int workers;
    private final long baseLatencyNanos;
    private final long faultLatencyNanos;
    private final double errorFraction;
    private final long seed;
    private final ThreadPoolExecutor pool;
    private final AtomicReference<URI> address = new AtomicReference<>();
    private volatile HttpServer server;
    private volatile Fault active = Fault.NONE;

    private final AtomicLong sequence = new AtomicLong();
    private final LongAdder received = new LongAdder();
    private final LongAdder served = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder wasted = new LongAdder();
    private final AtomicInteger restarts = new AtomicInteger();

    InventoryService(int workers, long baseLatencyNanos, long faultLatencyNanos, double errorFraction, long seed) throws IOException {
        this.workers = workers;
        this.baseLatencyNanos = baseLatencyNanos;
        this.faultLatencyNanos = faultLatencyNanos;
        this.errorFraction = errorFraction;
        this.seed = seed;
        AtomicInteger n = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "inventory-worker-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        listen();
    }

    private void listen() throws IOException {
        HttpServer s = LoopbackHttpServer.create(1024);
        s.createContext("/stock", this::handle);
        s.setExecutor(pool);
        s.start();
        server = s;
        address.set(URI.create("http://127.0.0.1:" + s.getAddress().getPort() + "/stock"));
    }

    /** Where clients should send requests now (changes after a restart). */
    URI address() {
        return address.get();
    }

    int workers() {
        return workers;
    }

    /** Requests waiting for a worker plus requests being served. */
    int inFlight() {
        return pool.getQueue().size() + pool.getActiveCount();
    }

    void inject(Fault fault) {
        if (fault == Fault.REFUSE) {
            HttpServer s = server;
            if (s != null) {
                s.stop(0);                                  // close the listener and every open connection
            }
        }
        active = fault;
    }

    void clear() throws IOException {
        Fault was = active;
        active = Fault.NONE;
        if (was == Fault.REFUSE) {
            restarts.incrementAndGet();
            listen();                                       // a fresh instance on a new port
        }
    }

    record Stats(long received, long served, long errors, long dropped, long wasted, int restarts) {
    }

    Stats stats() {
        return new Stats(received.sum(), served.sum(), errors.sum(), dropped.sum(), wasted.sum(), restarts.get());
    }

    private void handle(HttpExchange exchange) {
        received.increment();
        long seq = sequence.getAndIncrement();
        Fault fault = active;                               // the fault in force when a worker picks the request up
        try {
            if (fault == Fault.DROP) {
                dropped.increment();
                exchange.close();                           // no status line: the client sees the connection close
                return;
            }
            long latency = baseLatencyNanos + (fault == Fault.LATENCY ? faultLatencyNanos : 0);
            if (latency > 0) {
                Thread.sleep(Duration.ofNanos(latency));    // the worker is held for the whole time
            }
            if (fault == Fault.ERRORS && uniform(seed, seq) < errorFraction) {
                errors.increment();
                respond(exchange, 500, new byte[0]);
            } else {
                served.increment();
                respond(exchange, 200, BODY);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exchange.close();
        }
    }

    private void respond(HttpExchange exchange, int status, byte[] body) {
        try (OutputStream out = exchange.getResponseBody()) {
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            out.write(body);
        } catch (IOException e) {
            wasted.increment();                             // the client had already given up: work done for nobody
        } finally {
            exchange.close();
        }
    }

    /** Deterministic uniform [0, 1) for a request sequence number (SplitMix64 finaliser). */
    static double uniform(long seed, long seq) {
        long z = seed + (seq + 1) * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (z >>> 11) * 0x1.0p-53;
    }

    @Override
    public void close() {
        HttpServer s = server;
        if (s != null) {
            s.stop(0);
        }
        pool.shutdownNow();
    }
}
