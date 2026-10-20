package dev.forge.lab.scenarios.failure;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * The caller of the inventory service, with one of several resilience policies.
 *
 * <p>Every logical request has a deadline (the request timeout, measured from its intended send
 * time). Within it, the policy decides how long each attempt may take, whether and when to retry,
 * and whether to call the dependency at all. A request completes with 200, the last error status,
 * {@code 503} when short-circuited by an open breaker, or exceptionally on transport failure or
 * when the deadline passes.
 */
final class ResilientClient implements AutoCloseable {

    /**
     * @param attemptTimeoutNanos per-attempt timeout; 0 = wait until the request's deadline
     * @param maxRetries          retries after the first attempt
     * @param backoffBaseNanos    0 = retry immediately; else exponential backoff with full jitter
     * @param retryBudget         retries allowed per request on average (token bucket); &lt; 0 = unlimited
     * @param breaker             whether a circuit breaker guards the dependency
     */
    record Policy(long attemptTimeoutNanos, int maxRetries, long backoffBaseNanos, double retryBudget, boolean breaker) {
    }

    static final int SHORT_CIRCUITED = 503;

    private final Policy policy;
    private final Supplier<java.net.URI> address;
    private final HttpClient http;
    private final ExecutorService httpPool;
    private final ScheduledExecutorService timer;
    private final CircuitBreaker breaker;
    private final SplittableRandom jitter;
    private final long t0;
    private final long windowNanos;

    private final LongAdder attempts = new LongAdder();
    private final LongAdder retries = new LongAdder();
    private final LongAdder shortCircuited = new LongAdder();
    private final LongAdder budgetDenied = new LongAdder();
    private final AtomicLongArray attemptsByWindow;
    private double tokens;   // retry budget (guarded by this)

    ResilientClient(Policy policy, Supplier<java.net.URI> address, long seed, long t0, long windowNanos, int windows) {
        this.policy = policy;
        this.address = address;
        this.t0 = t0;
        this.windowNanos = windowNanos;
        this.attemptsByWindow = new AtomicLongArray(windows);
        AtomicInteger n = new AtomicInteger();
        this.httpPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "inventory-client-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(HttpClient.Builder.NO_PROXY)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(500))
                .executor(httpPool)
                .build();
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "retry-timer");
            t.setDaemon(true);
            return t;
        });
        this.breaker = policy.breaker()
                ? new CircuitBreaker(20, 10, 0.5, 1_000_000_000L, 3, System::nanoTime)
                : null;
        this.jitter = new SplittableRandom(seed);
        this.tokens = policy.retryBudget() >= 0 ? 10 : 0;   // a small initial allowance
    }

    /** One logical request with an overall deadline ({@code System.nanoTime()} based). */
    CompletableFuture<Integer> call(long deadlineNanos) {
        if (policy.retryBudget() >= 0) {
            synchronized (this) {
                tokens = Math.min(100, tokens + policy.retryBudget());   // each request earns a fraction of a retry
            }
        }
        CompletableFuture<Integer> result = new CompletableFuture<>();
        attempt(0, deadlineNanos, result);
        return result;
    }

    // forge:snippet attempt
    private void attempt(int n, long deadlineNanos, CompletableFuture<Integer> result) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            result.completeExceptionally(new HttpTimeoutException("request deadline passed"));
            return;
        }
        if (breaker != null && !breaker.tryAcquire()) {
            shortCircuited.increment();
            result.complete(SHORT_CIRCUITED);                // open breaker: fail fast, no call at all
            return;
        }
        long timeout = policy.attemptTimeoutNanos() == 0 ? remaining : Math.min(policy.attemptTimeoutNanos(), remaining);
        countAttempt(n);
        http.sendAsync(HttpRequest.newBuilder(address.get()).timeout(Duration.ofNanos(timeout)).GET().build(),
                        HttpResponse.BodyHandlers.discarding())
                .whenComplete((response, error) -> {
                    boolean ok = error == null && response.statusCode() == 200;
                    if (breaker != null) {
                        breaker.onResult(ok);
                    }
                    if (ok) {
                        result.complete(200);
                    } else if (n < policy.maxRetries() && retryable(error) && mayRetry()) {
                        retry(n + 1, deadlineNanos, result);
                    } else if (error != null) {
                        result.completeExceptionally(error);
                    } else {
                        result.complete(response.statusCode());
                    }
                });
    }
    // forge:end

    // forge:snippet retry
    private void retry(int n, long deadlineNanos, CompletableFuture<Integer> result) {
        retries.increment();
        if (policy.backoffBaseNanos() == 0) {
            attempt(n, deadlineNanos, result);               // immediately: every failure doubles the load
            return;
        }
        // exponential backoff with "full jitter": a random delay in [0, base × 2^(n−1)), so that
        // callers that failed together do not retry together
        long cap = policy.backoffBaseNanos() << Math.min(n - 1, 10);
        long delay;
        synchronized (jitter) {
            delay = jitter.nextLong(cap);
        }
        if (System.nanoTime() + delay >= deadlineNanos) {
            result.completeExceptionally(new HttpTimeoutException("no time left for another attempt"));
            return;
        }
        timer.schedule(() -> attempt(n, deadlineNanos, result), delay, TimeUnit.NANOSECONDS);
    }

    /** Retry budget: a retry spends one token; each request earns {@code retryBudget} tokens. */
    private boolean mayRetry() {
        if (policy.retryBudget() < 0) {
            return true;
        }
        synchronized (this) {
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
        }
        budgetDenied.increment();
        return false;                                        // budget exhausted: fail rather than amplify
    }
    // forge:end

    private static boolean retryable(Throwable error) {
        Throwable e = error;
        while (e instanceof java.util.concurrent.CompletionException && e.getCause() != null) {
            e = e.getCause();
        }
        // HTTP 500s, timeouts, refused and dropped connections are all retried; there is nothing else here
        return e == null || e instanceof IOException;
    }

    private void countAttempt(int n) {
        attempts.increment();
        int w = (int) ((System.nanoTime() - t0) / windowNanos);
        if (w >= 0 && w < attemptsByWindow.length()) {
            attemptsByWindow.incrementAndGet(w);
        }
    }

    record Stats(long attempts, long retries, long shortCircuited, long budgetDenied, long[] attemptsByWindow,
                 java.util.List<CircuitBreaker.Transition> transitions) {
    }

    Stats stats() {
        long[] byWindow = new long[attemptsByWindow.length()];
        for (int i = 0; i < byWindow.length; i++) {
            byWindow[i] = attemptsByWindow.get(i);
        }
        return new Stats(attempts.sum(), retries.sum(), shortCircuited.sum(), budgetDenied.sum(), byWindow,
                breaker == null ? java.util.List.of() : breaker.transitions());
    }

    CircuitBreaker.State breakerState() {
        return breaker == null ? null : breaker.state();
    }

    @Override
    public void close() {
        timer.shutdownNow();
        http.shutdownNow();
        httpPool.shutdownNow();
    }
}
