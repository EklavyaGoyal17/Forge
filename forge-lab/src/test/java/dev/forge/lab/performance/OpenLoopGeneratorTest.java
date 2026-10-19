package dev.forge.lab.performance;

import dev.forge.lab.performance.LatencyRecorder.Outcome;
import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongUnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The generator runs on a virtual clock against a fake transport that completes synchronously, so
 * every timestamp — and therefore every recorded latency — is exact and derivable by hand.
 */
class OpenLoopGeneratorTest {

    private static final long MS = 1_000_000L;
    private static final long S = 1_000_000_000L;

    /** Time only moves when the generator sleeps or the fake server "works". */
    static final class VirtualClock implements NanoClock {
        long now;

        @Override
        public long now() {
            return now;
        }

        @Override
        public void sleepUntil(long deadline) {
            now = Math.max(now, deadline);
        }
    }

    /** One connection to a server whose service time depends on when the request is sent. */
    private static OpenLoopGenerator.Transport synchronousServer(VirtualClock clock, LongUnaryOperator serviceNanosAt) {
        return (index, measured, timeoutNanos) -> {
            long service = serviceNanosAt.applyAsLong(clock.now);
            if (service >= timeoutNanos) {
                clock.now += timeoutNanos;
                return CompletableFuture.failedFuture(new HttpTimeoutException("request timed out"));
            }
            clock.now += service;
            return CompletableFuture.completedFuture(200);
        };
    }

    private static final Runnable NOT_CANCELLED = () -> { };

    @Test
    void latencyIsMeasuredFromTheIntendedSendTimeAcrossAServerStall() throws Exception {
        // 100 req/s for 10 s over ONE connection; every request takes 1 ms except the one sent at
        // t = 5 s, which takes 1 s. While it is stuck, 99 more requests become due and queue in the
        // generator. Request k after the stall (k = 1..110) is sent at 6 s + (k−1) ms and completes
        // at 6 s + k ms, but was due at 5 s + 10k ms: its latency is 1000 − 9k ms (991, 982, … 10).
        VirtualClock clock = new VirtualClock();
        var plan = new OpenLoopGenerator.Plan(100, 0, 10 * S, 10 * S, 1, 100 * MS);
        var transport = synchronousServer(clock, t -> t == 5 * S ? 1_000 * MS : MS);

        OpenLoopGenerator.Result r = OpenLoopGenerator.run(plan, clock, transport, NOT_CANCELLED);
        LatencyRecorder rec = r.recorder();
        Histogram corrected = rec.corrected();
        Histogram uncorrected = rec.uncorrected();

        assertThat(r.scheduled()).isEqualTo(1_000);
        assertThat(r.unfinished()).isZero();
        assertThat(rec.counts().offered()).isEqualTo(1_000);
        assertThat(rec.counts().ok()).isEqualTo(1_000);
        assertThat(corrected.getTotalCount()).isEqualTo(1_000);

        // From the actual send time, the stall is one slow request among 1000: p99 = 1 ms.
        assertThat(Histograms.ms(uncorrected.getValueAtPercentile(99))).isEqualTo(1.0);
        assertThat(Histograms.ms(uncorrected.getMaxValue())).isCloseTo(1_000.0, within(1.0));

        // From the intended send time, 111 requests (the stalled one + 110 delayed) are ≥ 10 ms.
        // Sorted ascending, the 990th value (HdrHistogram's p99 for n = 1000) is the 101st of
        // 10, 19, …, 991 → 10 + 9·100 = 910 ms.
        assertThat(Histograms.ms(corrected.getValueAtPercentile(99))).isCloseTo(910.0, within(1.0));
        assertThat(Histograms.ms(corrected.getValueAtPercentile(50))).isEqualTo(1.0);
        assertThat(corrected.getCountBetweenValues(10_000, Histograms.HIGHEST_MICROS)).isEqualTo(111);
        assertThat(Histograms.ms(corrected.getMaxValue())).isCloseTo(1_000.0, within(1.0));
    }

    @Test
    void withoutContentionCorrectedAndUncorrectedAgree() throws Exception {
        VirtualClock clock = new VirtualClock();
        var plan = new OpenLoopGenerator.Plan(100, 0, 2 * S, S, 1, 100 * MS);
        var r = OpenLoopGenerator.run(plan, clock, synchronousServer(clock, t -> 3 * MS), NOT_CANCELLED);
        assertThat(r.recorder().corrected().getMaxValue()).isEqualTo(r.recorder().uncorrected().getMaxValue());
        assertThat(Histograms.ms(r.recorder().corrected().getValueAtPercentile(100))).isCloseTo(3.0, within(0.003));   // 3 significant digits
    }

    @Test
    void warmUpRequestsAreSentButNotMeasured() throws Exception {
        VirtualClock clock = new VirtualClock();
        var plan = new OpenLoopGenerator.Plan(100, S, S, S, 4, 100 * MS);
        var r = OpenLoopGenerator.run(plan, clock, synchronousServer(clock, t -> MS), NOT_CANCELLED);
        assertThat(r.scheduled()).isEqualTo(200);
        assertThat(r.recorder().counts().offered()).isEqualTo(100);
        assertThat(r.recorder().corrected().getTotalCount()).isEqualTo(100);
        // all 100 measured requests completed inside the 1 s sustained window
        assertThat(r.recorder().achievedPerSecond()).isEqualTo(100.0);
    }

    @Test
    void aResponseThatExceedsTheTimeoutIsATimeoutRecordedAtTheTimeout() throws Exception {
        VirtualClock clock = new VirtualClock();
        var plan = new OpenLoopGenerator.Plan(100, 0, S, 200 * MS, 1, 100 * MS);
        var r = OpenLoopGenerator.run(plan, clock, synchronousServer(clock, t -> t == 500 * MS ? 3 * S : MS), NOT_CANCELLED);
        LatencyRecorder.Counts c = r.recorder().counts();
        assertThat(c.offered()).isEqualTo(100);
        assertThat(c.timeouts()).isEqualTo(1);
        assertThat(c.ok()).isEqualTo(99);
        assertThat(Histograms.ms(r.recorder().corrected().getMaxValue())).isCloseTo(200.0, within(0.2));
    }

    @Test
    void requestsThatCannotGetAConnectionBeforeTheirDeadlineAreTimeoutsWithoutBeingSent() throws Exception {
        // The first request never completes and holds the only connection. Each later request
        // waits (in real time) until its deadline for the connection, then is recorded unsent.
        VirtualClock clock = new VirtualClock();
        var plan = new OpenLoopGenerator.Plan(100, 0, 50 * MS, 30 * MS, 1, 10 * MS);
        OpenLoopGenerator.Transport neverCompletes = (i, m, timeout) -> new CompletableFuture<>();
        var r = OpenLoopGenerator.run(plan, clock, neverCompletes, NOT_CANCELLED);
        LatencyRecorder.Counts c = r.recorder().counts();
        assertThat(r.scheduled()).isEqualTo(5);
        assertThat(c.sent()).isZero();          // the one sent request never completed, so it is not in the recorder
        assertThat(c.timeouts()).isEqualTo(4);
        assertThat(r.unfinished()).isEqualTo(1);
        assertThat(r.recorder().uncorrected().getTotalCount()).isZero();
        assertThat(r.recorder().corrected().getTotalCount()).isEqualTo(4);
    }

    @Test
    void outcomesAreClassifiedFromStatusAndException() {
        assertThat(OpenLoopGenerator.classify(200, null)).isEqualTo(Outcome.OK);
        assertThat(OpenLoopGenerator.classify(503, null)).isEqualTo(Outcome.HTTP_ERROR);
        assertThat(OpenLoopGenerator.classify(null, new CompletionException(new HttpTimeoutException("t")))).isEqualTo(Outcome.TIMEOUT);
        assertThat(OpenLoopGenerator.classify(null, new CompletionException(new IOException("reset")))).isEqualTo(Outcome.FAILED);
    }

    @Test
    void seriesBucketsOfferedAndCompletedRequestsByWindow() throws Exception {
        VirtualClock clock = new VirtualClock();
        var plan = new OpenLoopGenerator.Plan(100, 0, S, S, 1, 250 * MS);
        var r = OpenLoopGenerator.run(plan, clock, synchronousServer(clock, t -> MS), NOT_CANCELLED);
        var series = r.recorder().series();
        assertThat(series).hasSize(4);
        assertThat(series).allSatisfy(p -> {
            assertThat(p.get("offered")).isEqualTo(25L);
            assertThat((Double) p.get("p99Ms")).isCloseTo(1.0, within(0.01));   // window histograms keep 2 significant digits
        });
        assertThat(series.stream().mapToLong(p -> (Long) p.get("completed")).sum()).isEqualTo(100);
    }
}
