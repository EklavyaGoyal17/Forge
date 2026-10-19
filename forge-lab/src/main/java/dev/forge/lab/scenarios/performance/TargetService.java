package dev.forge.lab.scenarios.performance;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.forge.lab.performance.CpuWork;
import dev.forge.lab.performance.Histograms;
import org.HdrHistogram.Histogram;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * The service under test: a {@code com.sun.net.httpserver.HttpServer} started inside the runner
 * JVM and bound to 127.0.0.1 on an ephemeral port, so no other host can be addressed.
 *
 * <p>{@code GET /work} performs calibrated CPU work and then a simulated downstream call of fixed
 * latency, in one of four server designs. The server measures, for requests the client marks as
 * measured: <b>queue wait</b> (task submitted to the worker executor → a worker starts it) and
 * <b>service time</b> (worker starts → response written, including the downstream wait). It also
 * sums how long worker threads were occupied by those requests, from which the Little's-law
 * capacity bound {@code workers / occupancy} follows.
 */
final class TargetService implements AutoCloseable {

    static final String MEASURED_HEADER = "x-forge-measured";
    static final int LARGE_POOL_FACTOR = 8;
    static final int MAX_LARGE_POOL = 256;
    private static final byte[] BODY = "ok".getBytes(StandardCharsets.US_ASCII);

    enum Design { BLOCKING_POOL, LARGE_POOL, ASYNC, VIRTUAL_THREADS }

    record Config(Design design, int poolSize, long cpuIterations, long downstreamNanos) {
    }

    /** One request as seen by a design's handler. */
    interface Request {
        /** Calibrated CPU-bound work on the current thread. */
        void cpuWork();

        /** A downstream call that blocks the calling thread for the configured latency. */
        void blockingDownstreamCall() throws InterruptedException;

        /** Writes {@code 200 ok}, closes the exchange and records the timings. Safe to call from any thread. */
        void respond();
    }

    interface Handler {
        void handle(Request request) throws Exception;
    }

    /** @param workers threads that can execute requests concurrently; 0 = unbounded (virtual threads). */
    private record Wiring(Executor executor, Handler handler, int workers) {
    }

    // forge:snippet blocking-pool
    private Wiring blockingPool(Config c) {
        Executor workers = measured(Executors.newFixedThreadPool(c.poolSize(), daemon("worker")));
        Handler handler = request -> {
            request.cpuWork();                    // CPU-bound part, on the worker thread
            request.blockingDownstreamCall();     // blocking I/O: the worker is held, doing nothing
            request.respond();
        };
        return new Wiring(workers, handler, c.poolSize());
    }
    // forge:end

    // forge:snippet large-pool
    private Wiring largePool(Config c) {
        int threads = Math.min(MAX_LARGE_POOL, c.poolSize() * LARGE_POOL_FACTOR);
        Executor workers = measured(Executors.newFixedThreadPool(threads, daemon("worker")));
        Handler handler = request -> {            // the same blocking handler, 8× the threads
            request.cpuWork();
            request.blockingDownstreamCall();
            request.respond();
        };
        return new Wiring(workers, handler, threads);
    }
    // forge:end

    // forge:snippet async
    private Wiring async(Config c) {
        Executor workers = measured(Executors.newFixedThreadPool(c.poolSize(), daemon("worker")));
        ScheduledExecutorService downstream = timer();   // stands in for a non-blocking client
        Handler handler = request -> {
            request.cpuWork();
            // Start the downstream call and return the worker to the pool immediately; when the
            // result arrives, a worker writes the response. No thread waits for the downstream.
            downstream.schedule(() -> workers.execute(request::respond),
                    c.downstreamNanos(), TimeUnit.NANOSECONDS);
        };
        return new Wiring(workers, handler, c.poolSize());
    }
    // forge:end

    // forge:snippet virtual-threads
    private Wiring virtualThreads(Config c) {
        Executor workers = measured(Executors.newVirtualThreadPerTaskExecutor());
        Handler handler = request -> {            // the same blocking code as the pool designs
            request.cpuWork();
            request.blockingDownstreamCall();     // a sleeping virtual thread unmounts; its carrier runs others
            request.respond();
        };
        return new Wiring(workers, handler, 0);
    }
    // forge:end

    // ------------------------------------------------------------------ infrastructure

    private static final class TaskTiming {
        long enqueued;
        long started;
        boolean measured;
    }

    private static final ThreadLocal<TaskTiming> TASK = new ThreadLocal<>();

    private final Config config;
    private final List<ExecutorService> pools = new ArrayList<>();
    private final HttpServer server;
    private final Wiring wiring;
    private final Histogram serviceTime = Histograms.newMicros();
    private final Histogram queueWait = Histograms.newMicros();
    private final LongAdder measuredRequests = new LongAdder();
    private final LongAdder measuredBusyNanos = new LongAdder();
    private final LongAdder serverErrors = new LongAdder();

    private TargetService(Config config) throws IOException {
        this.config = config;
        this.wiring = switch (config.design()) {
            case BLOCKING_POOL -> blockingPool(config);
            case LARGE_POOL -> largePool(config);
            case ASYNC -> async(config);
            case VIRTUAL_THREADS -> virtualThreads(config);
        };
        try {
            InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
            HttpServer s = HttpServer.create(new InetSocketAddress(loopback, 0), 1024);
            if (!s.getAddress().getAddress().isLoopbackAddress()) {
                s.stop(0);
                throw new IllegalStateException("target service must bind to loopback, got " + s.getAddress());
            }
            s.createContext("/work", this::dispatch);
            s.setExecutor(wiring.executor());
            this.server = s;
        } catch (IOException | RuntimeException e) {
            pools.forEach(ExecutorService::shutdownNow);
            throw e;
        }
    }

    static TargetService start(Config config) throws IOException {
        TargetService t = new TargetService(config);
        try {
            t.server.start();
        } catch (RuntimeException e) {
            t.close();
            throw e;
        }
        return t;
    }

    URI uri() {
        InetSocketAddress a = server.getAddress();
        return URI.create("http://" + a.getAddress().getHostAddress() + ":" + a.getPort() + "/work");
    }

    /** Threads able to run requests concurrently; 0 for virtual threads (unbounded). */
    int workers() {
        return wiring.workers();
    }

    record Stats(Histogram serviceTime, Histogram queueWait, long measuredRequests, long measuredBusyNanos, long serverErrors) {
        /** Mean worker-thread time per measured request, or NaN without data. */
        double occupancyMs() {
            return measuredRequests == 0 ? Double.NaN : measuredBusyNanos / 1e6 / measuredRequests;
        }
    }

    Stats stats() {
        synchronized (serviceTime) {
            return new Stats(serviceTime.copy(), queueWait.copy(), measuredRequests.sum(), measuredBusyNanos.sum(),
                    serverErrors.sum());
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
        pools.forEach(ExecutorService::shutdownNow);
    }

    /** Wraps a pool so that every task records when it was queued and how long it held a thread. */
    private Executor measured(ExecutorService pool) {
        pools.add(pool);
        return task -> {
            long enqueued = System.nanoTime();
            pool.execute(() -> {
                TaskTiming timing = new TaskTiming();
                timing.enqueued = enqueued;
                timing.started = System.nanoTime();
                TASK.set(timing);
                try {
                    task.run();
                } finally {
                    TASK.remove();
                    if (timing.measured) {
                        measuredBusyNanos.add(System.nanoTime() - timing.started);
                    }
                }
            });
        };
    }

    private ScheduledExecutorService timer() {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(daemon("downstream-timer"));
        pools.add(timer);
        return timer;
    }

    private void dispatch(HttpExchange exchange) {
        TaskTiming timing = TASK.get();
        long now = System.nanoTime();
        boolean measured = "1".equals(exchange.getRequestHeaders().getFirst(MEASURED_HEADER));
        if (timing != null) {
            timing.measured = measured;
        }
        Call call = new Call(exchange, measured, timing == null ? now : timing.enqueued, timing == null ? now : timing.started);
        try {
            wiring.handler().handle(call);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            call.fail();
        } catch (Exception e) {
            call.fail();
        }
    }

    private final class Call implements Request {
        private final HttpExchange exchange;
        private final boolean measured;
        private final long enqueued;
        private final long started;
        private final AtomicBoolean done = new AtomicBoolean();

        Call(HttpExchange exchange, boolean measured, long enqueued, long started) {
            this.exchange = exchange;
            this.measured = measured;
            this.enqueued = enqueued;
            this.started = started;
        }

        @Override
        public void cpuWork() {
            CpuWork.spin(config.cpuIterations());
        }

        @Override
        public void blockingDownstreamCall() throws InterruptedException {
            if (config.downstreamNanos() > 0) {
                Thread.sleep(Duration.ofNanos(config.downstreamNanos()));
            }
        }

        @Override
        public void respond() {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            TaskTiming timing = TASK.get();
            if (timing != null && measured) {
                timing.measured = true;   // the completion task of the async design also occupies a worker
            }
            try (OutputStream body = exchange.getResponseBody()) {
                exchange.sendResponseHeaders(200, BODY.length);
                body.write(BODY);
            } catch (IOException e) {
                serverErrors.increment();   // typically: the client gave up (timeout) and closed the connection
            } finally {
                exchange.close();
                if (measured) {
                    long end = System.nanoTime();
                    synchronized (serviceTime) {
                        serviceTime.recordValue(Histograms.toMicros(end - started));
                        queueWait.recordValue(Histograms.toMicros(started - enqueued));
                    }
                    measuredRequests.increment();
                }
            }
        }

        void fail() {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            serverErrors.increment();
            try {
                exchange.sendResponseHeaders(500, -1);
            } catch (IOException | RuntimeException ignored) {
                // connection already gone
            } finally {
                exchange.close();
            }
        }
    }

    private static ThreadFactory daemon(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "target-" + prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
