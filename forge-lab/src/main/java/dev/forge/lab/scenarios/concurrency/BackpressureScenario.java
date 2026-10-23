package dev.forge.lab.scenarios.concurrency;

import dev.forge.lab.concurrency.ControlledExecution.NamedTask;
import dev.forge.lab.concurrency.InterleavingExplorer;
import dev.forge.lab.concurrency.Probe;
import dev.forge.lab.scenario.Lab;
import dev.forge.lab.scenario.MetricSpec;
import dev.forge.lab.scenario.MetricSpec.Direction;
import dev.forge.lab.scenario.ParameterSpec;
import dev.forge.lab.scenario.RunContext;
import dev.forge.lab.scenario.RunSpec;
import dev.forge.lab.scenario.Scenario;
import dev.forge.lab.scenario.ScenarioDescriptor;
import dev.forge.lab.scenario.ScenarioResult;
import dev.forge.lab.scenario.ScenarioResult.InvariantSummary;
import dev.forge.lab.scenario.ScenarioResult.Trial;
import dev.forge.lab.scenario.VariantSpec;
import dev.forge.lab.stats.Summary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntFunction;

/**
 * Producer–consumer under overload: what a queue does when work arrives faster than it can be
 * served.
 *
 * <p>Producers emit items on a fixed schedule (open loop). Each item carries its <em>intended</em>
 * emission time, and latency is measured from that time, so a producer that is blocked by
 * backpressure does not hide the delay (no coordinated omission).
 *
 * <p>Invariants: every accepted item is consumed at most once and none is lost
 * ({@code accepted == consumed + remaining}), and no more than {@code capacity} items are ever
 * buffered (bounded memory).
 */
public final class BackpressureScenario implements Scenario {

    public static final String ID = "concurrency.backpressure";
    private static final String SRC = BackpressureScenario.class.getName() + "#";
    private static final int MAX_LATENCY_SAMPLES = 2_000_000;

    /** A work item: its sequence number and the time it was <em>supposed</em> to be produced. */
    record Item(int seq, long intendedNanos) {
    }

    /** The buffer between producers and consumers. Every implementation tracks its own peak depth. */
    interface Buffer {
        /** Hands an item to the buffer; returns false if it was rejected (dropped). May block. */
        boolean offer(Item item, Probe probe) throws InterruptedException;

        /** Next item, or null if none arrives within the timeout. */
        Item poll(long timeoutNanos, Probe probe) throws InterruptedException;

        int size();

        int maxDepth();
    }

    /** Tracks the largest depth observed right after an enqueue. */
    static final class Peak {
        private final AtomicInteger max = new AtomicInteger();

        void observe(int depth) {
            max.accumulateAndGet(depth, Math::max);
        }

        int get() {
            return max.get();
        }
    }

    // forge:snippet unbounded
    static final class UnboundedBuffer implements Buffer {
        private final LinkedBlockingQueue<Item> queue = new LinkedBlockingQueue<>(); // no limit at all
        private final Peak peak = new Peak();

        @Override
        public boolean offer(Item item, Probe probe) {
            queue.add(item);                    // always succeeds: overload turns into memory and latency
            peak.observe(queue.size());
            return true;
        }

        @Override
        public Item poll(long timeoutNanos, Probe probe) throws InterruptedException {
            return queue.poll(timeoutNanos, TimeUnit.NANOSECONDS);
        }

        @Override public int size() { return queue.size(); }
        @Override public int maxDepth() { return peak.get(); }
    }
    // forge:end

    // forge:snippet blocking
    static final class BlockingBuffer implements Buffer {
        private final ArrayBlockingQueue<Item> queue;
        private final Peak peak = new Peak();
        private final java.util.function.BooleanSupplier stopping;

        BlockingBuffer(int capacity, java.util.function.BooleanSupplier stopping) {
            this.queue = new ArrayBlockingQueue<>(capacity);
            this.stopping = stopping;
        }

        @Override
        public boolean offer(Item item, Probe probe) throws InterruptedException {
            // Backpressure: the producer waits until there is room. (Polled with a timeout only so the
            // trial can stop; semantically this is put().)
            while (!queue.offer(item, 5, TimeUnit.MILLISECONDS)) {
                if (stopping.getAsBoolean()) {
                    throw new InterruptedException("trial ended while waiting for space");
                }
            }
            peak.observe(queue.size());
            return true;
        }

        @Override
        public Item poll(long timeoutNanos, Probe probe) throws InterruptedException {
            return queue.poll(timeoutNanos, TimeUnit.NANOSECONDS);
        }

        @Override public int size() { return queue.size(); }
        @Override public int maxDepth() { return peak.get(); }
    }
    // forge:end

    // forge:snippet drop-newest
    static final class SheddingBuffer implements Buffer {
        private final ArrayBlockingQueue<Item> queue;
        private final Peak peak = new Peak();

        SheddingBuffer(int capacity) {
            this.queue = new ArrayBlockingQueue<>(capacity);
        }

        @Override
        public boolean offer(Item item, Probe probe) {
            boolean accepted = queue.offer(item);   // never waits: a full queue rejects new work
            if (accepted) {
                peak.observe(queue.size());
            }
            return accepted;
        }

        @Override
        public Item poll(long timeoutNanos, Probe probe) throws InterruptedException {
            return queue.poll(timeoutNanos, TimeUnit.NANOSECONDS);
        }

        @Override public int size() { return queue.size(); }
        @Override public int maxDepth() { return peak.get(); }
    }
    // forge:end

    // forge:snippet check-then-act
    static final class CheckThenActBuffer implements Buffer {
        private final ConcurrentLinkedQueue<Item> items = new ConcurrentLinkedQueue<>();
        private final AtomicInteger count = new AtomicInteger();  // every call is atomic...
        private final int capacity;
        private final Peak peak = new Peak();

        CheckThenActBuffer(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public boolean offer(Item item, Probe probe) {
            probe.read("count");
            int current = count.get();
            probe.value(current);
            if (current >= capacity) {          // ...but check-then-act is not: another producer can
                return false;                   // pass the same check before either one adds
            }
            probe.atomic("items", "add");
            items.add(item);
            probe.atomic("count", "increment");
            peak.observe(count.incrementAndGet());
            return true;
        }

        @Override
        public Item poll(long timeoutNanos, Probe probe) {
            probe.atomic("items", "poll");
            Item item = items.poll();
            if (item != null) {
                probe.atomic("count", "decrement");
                count.decrementAndGet();
            } else if (timeoutNanos > 0) {
                LockSupport.parkNanos(Math.min(timeoutNanos, 100_000));
            }
            return item;
        }

        @Override public int size() { return count.get(); }
        @Override public int maxDepth() { return peak.get(); }
    }
    // forge:end

    // forge:snippet locked-check-then-act
    static final class LockedCheckThenActBuffer implements Buffer {
        private final PlainDeque items = new PlainDeque();
        private final int capacity;
        private final Peak peak = new Peak();

        LockedCheckThenActBuffer(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public boolean offer(Item item, Probe probe) {
            probe.acquire("buffer");
            synchronized (this) {               // the check and the act form one atomic step
                probe.read("count");
                int current = items.size();
                probe.value(current);
                boolean accepted = current < capacity;
                if (accepted) {
                    probe.write("count", current + 1);
                    items.add(item);
                    peak.observe(current + 1);
                }
                probe.release("buffer");
                return accepted;
            }
        }

        @Override
        public Item poll(long timeoutNanos, Probe probe) {
            Item item;
            probe.acquire("buffer");
            synchronized (this) {
                item = items.poll();
                probe.release("buffer");
            }
            if (item == null && timeoutNanos > 0) {
                LockSupport.parkNanos(Math.min(timeoutNanos, 100_000));
            }
            return item;
        }

        @Override public synchronized int size() { return items.size(); }
        @Override public int maxDepth() { return peak.get(); }
    }
    // forge:end

    /** Unsynchronized FIFO; all thread safety in the locked variant comes from its monitor. */
    static final class PlainDeque {
        private final java.util.ArrayDeque<Item> d = new java.util.ArrayDeque<>();

        void add(Item i) { d.addLast(i); }
        Item poll() { return d.pollFirst(); }
        int size() { return d.size(); }
    }

    private static IntFunction<Buffer> factory(String variant, java.util.function.BooleanSupplier stopping) {
        return switch (variant) {
            case "unbounded" -> capacity -> new UnboundedBuffer();
            case "blocking" -> capacity -> new BlockingBuffer(capacity, stopping);
            case "drop-newest" -> SheddingBuffer::new;
            case "check-then-act" -> CheckThenActBuffer::new;
            case "locked-check-then-act" -> LockedCheckThenActBuffer::new;
            default -> throw new IllegalArgumentException("unknown variant " + variant);
        };
    }

    private static final ScenarioDescriptor DESCRIPTOR = new ScenarioDescriptor(
            ID, 2, Lab.CONCURRENCY,           // v2: producers park instead of spinning
            "Backpressure: producers faster than consumers",
            "Producers emit work on a fixed schedule faster than consumers can serve it. Compare an unbounded queue, a blocking bounded queue (backpressure), load shedding, and a hand-rolled bounded buffer with a check-then-act race.",
            "When work arrives faster than it can be processed, where does the excess go — memory, latency, the producer, or the floor — and which choice keeps the system's promises?",
            "Every accepted item is consumed at most once and none is lost, and no more than `capacity` items are ever buffered.",
            List.of(
                    ParameterSpec.choice("mode", "Mode", "stress", List.of("stress", "explore"),
                            "stress: real producers and consumers under overload. explore: every interleaving of a few producers offering into a tiny buffer (hand-rolled buffers only)."),
                    ParameterSpec.integer("producers", "Producers", 4, 1, 16, "threads", "Threads emitting work on a fixed schedule.").onlyFor("stress"),
                    ParameterSpec.integer("ratePerProducer", "Rate per producer", 5_000, 100, 100_000, "items/s",
                            "Scheduled emission rate of each producer. Offered load = producers × rate.").onlyFor("stress"),
                    ParameterSpec.integer("consumers", "Consumers", 2, 1, 16, "threads", "Threads serving items.").onlyFor("stress"),
                    ParameterSpec.integer("serviceMicros", "Service time", 200, 1, 10_000, "µs",
                            "CPU time each consumer spends per item (busy work, so it is not distorted by timer granularity). Nominal capacity = consumers × 10⁶ / service time.").onlyFor("stress"),
                    ParameterSpec.integer("capacity", "Buffer capacity", 1_000, 1, 100_000, "items",
                            "Bound for the bounded buffers; the promise the unbounded queue is measured against.").onlyFor("stress"),
                    ParameterSpec.integer("durationMs", "Trial duration", 1_000, 200, 10_000, "ms", "How long producers emit per trial.").onlyFor("stress"),
                    ParameterSpec.integer("exploreProducers", "Producers", 2, 2, 3, "threads", "Producers in the explored program.").onlyFor("explore"),
                    ParameterSpec.integer("exploreOffers", "Offers per producer", 1, 1, 2, "items", "Items each producer offers.").onlyFor("explore"),
                    ParameterSpec.integer("exploreCapacity", "Buffer capacity", 1, 1, 3, "items", "Capacity of the explored buffer.").onlyFor("explore"),
                    ParameterSpec.integer("maxSchedules", "Schedule budget", 5_000, 1, 50_000, "schedules", "Upper bound on executions.").onlyFor("explore")),
            List.of(
                    new VariantSpec("unbounded", "Unbounded queue", "LinkedBlockingQueue without a limit: never refuses work.", false, SRC + "unbounded").onlyFor("stress"),
                    new VariantSpec("blocking", "Bounded, blocking (backpressure)", "ArrayBlockingQueue; producers wait for space.", true, SRC + "blocking").onlyFor("stress"),
                    new VariantSpec("drop-newest", "Bounded, shed load", "ArrayBlockingQueue.offer(); a full queue rejects new items.", true, SRC + "drop-newest").onlyFor("stress"),
                    new VariantSpec("check-then-act", "Hand-rolled bound (check-then-act)", "Atomic counter + concurrent queue, but the capacity check and the add are separate steps.", false, SRC + "check-then-act"),
                    new VariantSpec("locked-check-then-act", "Hand-rolled bound (locked)", "The same buffer with check and add inside one synchronized block.", true, SRC + "locked-check-then-act")),
            List.of(
                    new MetricSpec("p99LatencyMs", "p99 latency", "ms", Direction.LOWER_IS_BETTER, "99th percentile of (consumer pickup time − intended emission time) over consumed items."),
                    new MetricSpec("p50LatencyMs", "p50 latency", "ms", Direction.LOWER_IS_BETTER, "Median of the same latency."),
                    new MetricSpec("maxLatencyMs", "Max latency", "ms", Direction.LOWER_IS_BETTER, "Largest latency of a consumed item."),
                    new MetricSpec("throughputPerSec", "Throughput", "items/s", Direction.HIGHER_IS_BETTER, "Items consumed per second of trial time."),
                    new MetricSpec("maxQueueDepth", "Peak buffered items", "items", Direction.LOWER_IS_BETTER, "Largest depth observed right after an enqueue."),
                    new MetricSpec("droppedPercent", "Dropped", "%", Direction.LOWER_IS_BETTER, "Share of emitted items the buffer rejected."),
                    new MetricSpec("producerLagMs", "Producer lag", "ms", Direction.LOWER_IS_BETTER, "How far behind its schedule the slowest producer was when the trial ended."),
                    new MetricSpec("consumed", "Consumed", "items", Direction.NEUTRAL, "Items consumers picked up."),
                    new MetricSpec("remaining", "Left in buffer", "items", Direction.NEUTRAL, "Accepted items still buffered when the trial ended."),
                    new MetricSpec("schedulesExplored", "Schedules explored", "", Direction.NEUTRAL, "Explorer mode."),
                    new MetricSpec("violatingSchedules", "Violating schedules", "", Direction.LOWER_IS_BETTER, "Explorer mode: schedules that overfilled the buffer or lost a count.")),
            "p99LatencyMs",
            new ScenarioDescriptor.TrialPolicy(3, 20, 1, 5),
            180,
            """
            If items arrive at rate λ and the system can serve μ, then for λ > μ the excess λ − μ has to go somewhere. \
            An **unbounded queue** stores it: memory and waiting time grow linearly for as long as the overload lasts, \
            and nothing tells the producer to slow down. A **blocking bounded queue** applies backpressure: memory is \
            bounded, but the waiting moves upstream into the producer — measured from the time work *should* have been \
            produced, latency still grows. **Load shedding** keeps both memory and latency bounded by refusing work, \
            which is only acceptable if the caller can retry or the work can be lost.

            The hand-rolled buffers show a separate, classic bug: a capacity check followed by an add is a \
            check-then-act race even when each individual operation is atomic. Explorer mode finds the schedule in \
            which two producers both pass the check.""",
            List.of(
                    "Nominal consumer capacity assumes the busy-work service time is exact; JIT warm-up, OS scheduling and CPU frequency changes make real capacity lower and variable.",
                    "Producers, consumers and the measuring code share one JVM and one CPU; with more busy consumers than free cores, producers fall behind for reasons unrelated to the queue.",
                    "Producers park until each item is due rather than spinning, so they do not steal CPU from consumers (an earlier spinning design made throughput depend on whether producers blocked). Timer oversleep — roughly 0.05 ms on Linux, 1 ms or more on Windows — is therefore included in measured latency.",
                    "Latency is measured from the intended emission time (no coordinated omission). A closed-loop benchmark that only times successful sends would report far better numbers for the blocking variant.",
                    "The check-then-act race only manifests when two producers interleave between the check and the add; under stress it may or may not appear in a given trial. Explorer mode is the deterministic evidence.",
                    "The unbounded queue's growth is limited here by the trial duration and the heap cap; in production it ends in an OutOfMemoryError or unbounded latency."));

    @Override
    public ScenarioDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ScenarioResult run(RunSpec spec, RunContext ctx) throws Exception {
        if (DESCRIPTOR.variant(spec.variant()).map(v -> !v.supportsMode(spec.stringParam("mode"))).orElse(true)) {
            throw new IllegalArgumentException("variant " + spec.variant() + " does not support mode " + spec.stringParam("mode"));
        }
        return spec.stringParam("mode").equals("explore") ? explore(spec, ctx) : stress(spec, ctx);
    }

    // ---------------------------------------------------------------- stress mode

    private ScenarioResult stress(RunSpec spec, RunContext ctx) throws InterruptedException {
        int producers = spec.intParam("producers");
        int consumers = spec.intParam("consumers");
        long rate = spec.longParam("ratePerProducer");
        long serviceNanos = spec.longParam("serviceMicros") * 1_000L;
        int capacity = spec.intParam("capacity");
        long durationNanos = spec.longParam("durationMs") * 1_000_000L;
        double offered = (double) producers * rate;
        double nominalCapacity = consumers * 1e9 / serviceNanos;
        ctx.info(String.format("offered load %.0f items/s vs nominal consumer capacity %.0f items/s (ρ = %.2f)",
                offered, nominalCapacity, offered / nominalCapacity));

        ScenarioResult.Builder result = ScenarioResult.builder();
        int total = spec.warmupTrials() + spec.trials();
        for (int t = 0; t < total; t++) {
            ctx.checkCancelled();
            boolean warmup = t < spec.warmupTrials();
            TrialOutcome o = runTrial(spec.variant(), producers, consumers, rate, serviceNanos, capacity, durationNanos, ctx);
            ctx.progress(t + 1, total, warmup ? "warm-up" : "measured");
            if (warmup) {
                continue;
            }
            boolean conserved = o.accepted == o.consumed + o.remaining && o.duplicates == 0;
            boolean bounded = o.maxDepth <= capacity;
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("p50LatencyMs", o.percentileMs(50));
            m.put("p99LatencyMs", o.percentileMs(99));
            m.put("maxLatencyMs", o.percentileMs(100));
            m.put("throughputPerSec", o.consumed / (o.elapsedNanos / 1e9));
            m.put("maxQueueDepth", (double) o.maxDepth);
            m.put("droppedPercent", o.emitted == 0 ? 0.0 : 100.0 * o.dropped / o.emitted);
            m.put("producerLagMs", o.maxProducerLagNanos / 1e6);
            m.put("consumed", (double) o.consumed);
            m.put("remaining", (double) o.remaining);
            List<String> problems = new ArrayList<>();
            if (!bounded) {
                problems.add("peak depth " + o.maxDepth + " exceeded capacity " + capacity);
            }
            if (o.duplicates > 0) {
                problems.add(o.duplicates + " items consumed twice");
            }
            if (o.accepted != o.consumed + o.remaining) {
                problems.add("accepted " + o.accepted + " ≠ consumed " + o.consumed + " + remaining " + o.remaining);
            }
            result.trial(new Trial(t - spec.warmupTrials(), m, conserved && bounded,
                    problems.isEmpty() ? null : String.join("; ", problems)));
        }

        InvariantSummary inv = InvariantSummary.of(DESCRIPTOR.invariant(), result.trials());
        result.invariant(inv);
        result.observation(String.format("Configured offered load %.0f items/s against a nominal consumer capacity of %.0f items/s (ρ = %.2f).",
                offered, nominalCapacity, offered / nominalCapacity));
        result.observation("Invariant violated in " + inv.violations() + " of " + inv.checked() + " measured trials.");
        double[] p99 = result.trials().stream().mapToDouble(x -> x.metrics().get("p99LatencyMs")).sorted().toArray();
        double[] depth = result.trials().stream().mapToDouble(x -> x.metrics().get("maxQueueDepth")).sorted().toArray();
        double[] dropped = result.trials().stream().mapToDouble(x -> x.metrics().get("droppedPercent")).sorted().toArray();
        double[] lag = result.trials().stream().mapToDouble(x -> x.metrics().get("producerLagMs")).sorted().toArray();
        double[] tput = result.trials().stream().mapToDouble(x -> x.metrics().get("throughputPerSec")).sorted().toArray();
        result.observation(String.format("Median across trials: p99 latency %.1f ms, peak buffered %.0f items (capacity %d), dropped %.1f%%, producer lag %.1f ms, throughput %.0f items/s.",
                Summary.median(p99), Summary.median(depth), capacity, Summary.median(dropped), Summary.median(lag), Summary.median(tput)));
        if (Summary.median(lag) > 10 && "blocking".equals(spec.variant())) {
            result.observation("Producers ended behind schedule: backpressure bounded the buffer, and the waiting appears as producer lag and latency measured from the intended emission time.");
        }
        return result.build();
    }

    private static final class TrialOutcome {
        long emitted;
        long accepted;
        long dropped;
        long consumed;
        long remaining;
        long duplicates;
        int maxDepth;
        long maxProducerLagNanos;
        long elapsedNanos;
        long[] latencies = new long[0];

        double percentileMs(double p) {
            if (latencies.length == 0) {
                return 0;
            }
            int idx = (int) Math.ceil(p / 100.0 * latencies.length) - 1;
            return latencies[Math.max(0, Math.min(latencies.length - 1, idx))] / 1e6;
        }
    }

    private static TrialOutcome runTrial(String variant, int producers, int consumers, long rate, long serviceNanos,
                                         int capacity, long durationNanos, RunContext ctx) throws InterruptedException {
        java.util.concurrent.atomic.AtomicBoolean stopProducing = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean stopConsuming = new java.util.concurrent.atomic.AtomicBoolean();
        Buffer buffer = factory(variant, stopProducing::get).apply(capacity);
        int maxItems = (int) Math.min(Integer.MAX_VALUE - 8, rate * producers * (durationNanos / 1_000_000_000.0 + 1) + 16);
        AtomicLongArray seen = new AtomicLongArray(maxItems / 64 + 1);   // one bit per item
        AtomicInteger nextSeq = new AtomicInteger();
        AtomicLong emitted = new AtomicLong();
        AtomicLong accepted = new AtomicLong();
        AtomicLong dropped = new AtomicLong();
        AtomicLong duplicates = new AtomicLong();
        long[] lagByProducer = new long[producers];
        LatencyLog[] logs = new LatencyLog[consumers];
        CountDownLatch ready = new CountDownLatch(producers + consumers);
        CountDownLatch go = new CountDownLatch(1);
        long[] start = new long[1];
        long periodNanos = 1_000_000_000L / rate;

        List<Thread> threads = new ArrayList<>();
        for (int c = 0; c < consumers; c++) {
            LatencyLog log = logs[c] = new LatencyLog();
            Thread th = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    while (!stopConsuming.get()) {
                        Item item = buffer.poll(1_000_000, Probe.NONE);
                        if (item == null) {
                            continue;
                        }
                        log.add(System.nanoTime() - item.intendedNanos());
                        long bit = 1L << (item.seq() & 63);
                        if ((seen.getAndAccumulate(item.seq() >>> 6, bit, (a, b) -> a | b) & bit) != 0) {
                            duplicates.incrementAndGet();
                        }
                        long busyUntil = System.nanoTime() + serviceNanos;   // the "work"
                        while (System.nanoTime() < busyUntil) {
                            Thread.onSpinWait();
                        }
                    }
                } catch (InterruptedException ignored) {
                    // trial ended
                }
            }, "consumer-" + c);
            th.setDaemon(true);
            threads.add(th);
        }
        for (int p = 0; p < producers; p++) {
            int idx = p;
            Thread th = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    long t0 = start[0] + idx * (periodNanos / Math.max(1, producers));   // stagger producers
                    for (long k = 0; ; k++) {
                        long intended = t0 + k * periodNanos;
                        if (intended - start[0] >= durationNanos || stopProducing.get()) {
                            break;
                        }
                        // Park (never spin) until the item is due. Spinning producers would compete with the
                        // consumers for CPU and make throughput depend on whether producers happen to block.
                        // Timer oversleep is not hidden: latency is measured from the intended time.
                        long wait;
                        while ((wait = intended - System.nanoTime()) > 0) {
                            LockSupport.parkNanos(wait);
                        }
                        Item item = new Item(nextSeq.getAndIncrement(), intended);
                        emitted.incrementAndGet();
                        if (buffer.offer(item, Probe.NONE)) {
                            accepted.incrementAndGet();
                        } else {
                            dropped.incrementAndGet();
                        }
                        lagByProducer[idx] = System.nanoTime() - intended;
                    }
                } catch (InterruptedException ignored) {
                    // stopped while blocked on a full buffer: the item was emitted but never accepted
                }
            }, "producer-" + p);
            th.setDaemon(true);
            threads.add(th);
        }
        threads.forEach(Thread::start);
        ready.await();
        start[0] = System.nanoTime() + 5_000_000;   // common start, slightly in the future
        go.countDown();

        long end = start[0] + durationNanos;
        while (System.nanoTime() < end) {
            if (System.nanoTime() - ctx.deadlineNanos() > 0) {
                break;
            }
            Thread.sleep(Math.max(1, Math.min(20, (end - System.nanoTime()) / 1_000_000)));
        }
        stopProducing.set(true);
        for (Thread th : threads.subList(consumers, threads.size())) {
            th.join(2_000);
        }
        stopConsuming.set(true);
        for (Thread th : threads.subList(0, consumers)) {
            th.join(2_000);
        }
        long elapsed = System.nanoTime() - start[0];

        TrialOutcome o = new TrialOutcome();
        o.emitted = emitted.get();
        o.accepted = accepted.get();
        o.dropped = dropped.get();
        o.duplicates = duplicates.get();
        o.remaining = buffer.size();
        o.maxDepth = buffer.maxDepth();
        o.elapsedNanos = elapsed;
        o.maxProducerLagNanos = Arrays.stream(lagByProducer).max().orElse(0);
        long consumed = 0;
        for (LatencyLog log : logs) {
            consumed += log.count;
        }
        o.consumed = consumed;
        o.latencies = LatencyLog.merge(logs);
        Arrays.sort(o.latencies);
        return o;
    }

    /** Per-consumer latency samples; no sharing, so recording adds no contention. */
    private static final class LatencyLog {
        long[] values = new long[4096];
        int size;
        long count;

        void add(long v) {
            count++;
            if (size == values.length) {
                if (size >= MAX_LATENCY_SAMPLES) {
                    return;
                }
                values = Arrays.copyOf(values, Math.min(MAX_LATENCY_SAMPLES, size * 2));
            }
            values[size++] = v;
        }

        static long[] merge(LatencyLog[] logs) {
            int n = 0;
            for (LatencyLog l : logs) {
                n += l.size;
            }
            long[] out = new long[n];
            int i = 0;
            for (LatencyLog l : logs) {
                System.arraycopy(l.values, 0, out, i, l.size);
                i += l.size;
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- explorer mode

    private ScenarioResult explore(RunSpec spec, RunContext ctx) {
        int producers = spec.intParam("exploreProducers");
        int offers = spec.intParam("exploreOffers");
        int capacity = spec.intParam("exploreCapacity");
        int budget = spec.intParam("maxSchedules");
        ctx.info("explore: " + producers + " producers × " + offers + " offer(s) into capacity " + capacity);

        record State(Buffer buffer, AtomicInteger accepted) {
        }
        InterleavingExplorer.Report report = InterleavingExplorer.explore(
                () -> new State(factory(spec.variant(), () -> false).apply(capacity), new AtomicInteger()),
                s -> {
                    List<NamedTask> tasks = new ArrayList<>();
                    for (int p = 0; p < producers; p++) {
                        int base = p * offers;
                        tasks.add(new NamedTask("P" + (p + 1), probe -> {
                            for (int k = 0; k < offers; k++) {
                                try {
                                    if (s.buffer().offer(new Item(base + k, 0), probe)) {
                                        s.accepted().incrementAndGet();
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }));
                    }
                    return tasks;
                },
                s -> {
                    int buffered = drain(s.buffer());
                    boolean ok = s.buffer().maxDepth() <= capacity && buffered == s.accepted().get();
                    return new InterleavingExplorer.Check(ok, "buffered " + buffered + " of capacity " + capacity
                            + (buffered == s.accepted().get() ? "" : ", but " + s.accepted().get() + " offers accepted"));
                },
                budget, 10_000, ctx::checkCancelled);

        ScenarioResult.Builder result = ScenarioResult.builder();
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("schedulesExplored", (double) report.schedulesExplored());
        m.put("violatingSchedules", (double) report.violations());
        result.trial(new Trial(0, m, report.violations() == 0 && report.errors() == 0, null));
        result.invariant(new InvariantSummary(DESCRIPTOR.invariant(), report.schedulesExplored(), report.violations(),
                report.violations() == 0));
        result.detail("explorer", ExplorerDetails.of(report, capacity));
        result.observation((report.exhaustive() ? "Exhaustive: all " : "Non-exhaustive: budget reached after ")
                + report.schedulesExplored() + " schedules at yield-point granularity.");
        result.observation(report.violations() + " of " + report.schedulesExplored() + " schedules overfill the buffer or lose an item.");
        report.outcomeCounts().forEach((k, v) -> result.observation("Outcome \"" + k + "\": " + v + " schedules."));
        return result.build();
    }

    private static int drain(Buffer b) {
        int n = 0;
        while (true) {
            try {
                if (b.poll(0, Probe.NONE) == null) {
                    return n;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return n;
            }
            n++;
        }
    }
}
