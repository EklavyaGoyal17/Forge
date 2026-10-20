package dev.forge.lab.jvm;

import dev.forge.lab.jvm.JfrSummary.ContentionRow;
import dev.forge.lab.jvm.JfrSummary.Marker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Records a small, real JFR recording of this JVM with workloads whose JFR footprint is certain —
 * an explicit {@code System.gc()}, a monitor held across a sleep while another thread waits for it,
 * the same for a {@link ReentrantLock}, markers — and checks the summary's structure. Nothing here
 * asserts a duration beyond "the 150 ms hold was recorded as at least the 1 ms threshold".
 */
class JfrSummariserTest {

    @TempDir
    static Path work;

    static JfrSummary summary;
    static Path file;
    static JfrCapture.Settings settings = JfrCapture.Settings.defaults();

    /** Kept reachable so the allocation loop cannot be removed. */
    static volatile Object sink;

    static final class Monitor {
    }

    @BeforeAll
    static void record() throws Exception {
        try (JfrCapture capture = JfrCapture.start(work, "test", settings)) {
            JfrMarker.mark("begin");
            allocateBuffers();
            spinUntilSampled();
            System.gc();
            holdMonitorWhileAnotherThreadWaits();
            holdReentrantLockWhileAnotherThreadWaits();
            JfrMarker.mark("end");
            file = capture.stop();
            summary = JfrSummariser.summarise(file, capture.startedAt(), settings,
                    JfrSummariser.Options.forPackage("dev.forge.lab.jvm"));
        }
    }

    static void allocateBuffers() {
        for (int i = 0; i < 200_000; i++) {
            sink = new byte[1024];
        }
    }

    /**
     * The execution sampler does not always start sampling within a fixed window (seen on JDK 21 in a
     * container: 0 samples in 300 ms), so spin until the running recording holds a sample of
     * {@link #spin}, for at most 5 s.
     */
    static void spinUntilSampled() throws java.io.IOException {
        for (int i = 0; i < 25; i++) {
            spin(200);
            if (spinSamples() > 0) {
                return;
            }
        }
    }

    static long spinSamples() throws java.io.IOException {
        long n = 0;
        for (jdk.jfr.Recording r : jdk.jfr.FlightRecorder.getFlightRecorder().getRecordings()) {
            if (r.getState() != jdk.jfr.RecordingState.RUNNING) {
                continue;
            }
            Path probe = Files.createTempFile(work, "probe", ".jfr");
            r.dump(probe);
            for (jdk.jfr.consumer.RecordedEvent e : jdk.jfr.consumer.RecordingFile.readAllEvents(probe)) {
                if (e.getEventType().getName().equals("jdk.ExecutionSample") && e.getStackTrace() != null
                        && e.getStackTrace().getFrames().stream().anyMatch(f -> f.getMethod().getName().equals("spin")
                        && f.getMethod().getType().getName().endsWith("JfrSummariserTest"))) {
                    n++;
                }
            }
            Files.deleteIfExists(probe);
        }
        return n;
    }

    static long spin(long millis) {
        long end = System.nanoTime() + millis * 1_000_000;
        long x = 0;
        while (System.nanoTime() < end) {
            x += Long.numberOfTrailingZeros(x + 1);
        }
        return x;
    }

    static void holdMonitorWhileAnotherThreadWaits() throws InterruptedException {
        Monitor m = new Monitor();
        CountDownLatch held = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> {
            synchronized (m) {
                held.countDown();
                sleep(150);
            }
        });
        held.await();
        Thread waiter = Thread.ofPlatform().start(() -> {
            synchronized (m) {
                sink = m;
            }
        });
        holder.join();
        waiter.join();
    }

    static void holdReentrantLockWhileAnotherThreadWaits() throws InterruptedException {
        ReentrantLock lock = new ReentrantLock();
        CountDownLatch held = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> {
            lock.lock();
            try {
                held.countDown();
                sleep(150);
            } finally {
                lock.unlock();
            }
        });
        held.await();
        Thread waiter = Thread.ofPlatform().start(() -> {
            lock.lock();
            lock.unlock();
        });
        holder.join();
        waiter.join();
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void recordingMetadataDescribesTheFileAndTheSettingsUsed() throws Exception {
        assertThat(summary.recording().fileBytes()).isEqualTo(Files.size(file)).isPositive();
        assertThat(summary.recording().durationMs()).isPositive();
        assertThat(summary.recording().settings())
                .containsEntry("jdk.ExecutionSample#period", "10 ms")
                .containsEntry("jdk.JavaMonitorEnter#threshold", "1 ms");
        assertThat(summary.recording().eventCounts()).containsKeys("jdk.GarbageCollection", JfrMarker.NAME);
        // tables cover the region between the two markers
        assertThat(summary.recording().windowStartMs()).isEqualTo(summary.markers().get(0).atMs());
        assertThat(summary.recording().windowEndMs()).isEqualTo(summary.markers().get(1).atMs());
    }

    @Test
    void explicitGcIsRecordedWithCauseAndHeapBeforeAndAfter() {
        assertThat(summary.gc().collections()).isPositive();
        assertThat(summary.gc().pauses()).hasSize((int) summary.gc().collections());
        var explicit = summary.gc().pauses().stream().filter(p -> "System.gc()".equals(p.cause())).findFirst();
        assertThat(explicit).isPresent();
        assertThat(explicit.get().heapBeforeBytes()).isNotNull();
        assertThat(explicit.get().heapAfterBytes()).isNotNull();
        assertThat(summary.gc().totalPauseMs()).isGreaterThanOrEqualTo(summary.gc().maxPauseMs());
        assertThat(summary.gc().youngCollector()).isNotBlank();
    }

    @Test
    void blockedSynchronizedEnterIsAttributedToTheMonitorClassAndTheWaitingCode() {
        var monitor = summary.locks().monitorEnter();
        assertThat(monitor.thresholdMs()).isEqualTo(1.0);
        ContentionRow row = monitor.rows().stream()
                .filter(r -> r.lockClass().equals(Monitor.class.getName())).findFirst().orElseThrow();
        assertThat(row.events()).isGreaterThanOrEqualTo(1);
        assertThat(row.maxMs()).isGreaterThanOrEqualTo(1.0);
        assertThat(row.appFrame()).contains("JfrSummariserTest.");
        assertThat(row.stackTop()).isNotEmpty();
    }

    @Test
    void parkedReentrantLockWaitIsRecordedAsThreadPark() {
        var park = summary.locks().threadPark();
        assertThat(park.rows()).anySatisfy(r -> {
            assertThat(r.lockClass()).startsWith("java.util.concurrent.locks.ReentrantLock");
            assertThat(r.appFrame()).contains("JfrSummariserTest.");
        });
    }

    @Test
    void allocationSamplesAreAttributedToClassesAndSites() {
        var a = summary.allocation();
        assertThat(a.samples()).isPositive();
        // the allocating thread's first sample is dropped: its weight reaches back before the recording
        assertThat(a.discardedFirstSamples()).isPositive();
        assertThat(a.sampledBytes()).isPositive();
        assertThat(a.byClass()).isNotEmpty();
        assertThat(a.byClass().stream().mapToDouble(JfrSummary.AllocationClassRow::share).sum()).isLessThanOrEqualTo(1.0 + 1e-9);
        assertThat(a.sites()).anySatisfy(s -> assertThat(s.appFrame()).contains("allocateBuffers"));
        // rows are sorted by weight, heaviest first
        for (int i = 1; i < a.byClass().size(); i++) {
            assertThat(a.byClass().get(i).weightBytes()).isLessThanOrEqualTo(a.byClass().get(i - 1).weightBytes());
        }
    }

    @Test
    void cpuSamplesIncludeTheSpinningMethod() {
        var cpu = summary.cpu();
        assertThat(cpu.samples()).isPositive();
        assertThat(cpu.periodMs()).isEqualTo(10.0);
        assertThat(cpu.topTotal()).anySatisfy(r -> assertThat(r.frame()).endsWith("JfrSummariserTest.spin"));
        assertThat(cpu.topSelf()).allSatisfy(r -> assertThat(r.frameTypes()).isNotEmpty());
        assertThat(cpu.hotPaths()).allSatisfy(p -> assertThat(p.frames()).hasSizeBetween(1, 6));
    }

    @Test
    void markersAreOrderedAndCarryNanoTime() {
        List<Marker> m = summary.markers();
        assertThat(m).extracting(Marker::label).containsExactly("begin", "end");
        assertThat(m.get(1).nanoTime()).isGreaterThan(m.get(0).nanoTime());
        assertThat(m.get(1).atMs()).isGreaterThan(m.get(0).atMs());
    }

    @Test
    void compilationsAreFilteredToTheApplicationPackage() {
        assertThat(summary.compilations()).allSatisfy(c -> {
            assertThat(c.method()).startsWith("dev.forge.lab.jvm.");
            assertThat(c.level()).isBetween(1, 4);
            assertThat(c.endAtMs()).isGreaterThanOrEqualTo(c.atMs());
        });
    }

    @Test
    void summarisingTheSameFileTwiceGivesTheSameSummary() throws Exception {
        JfrSummary again = JfrSummariser.summarise(file, null, settings, JfrSummariser.Options.forPackage("dev.forge.lab.jvm"));
        JfrSummary again2 = JfrSummariser.summarise(file, null, settings, JfrSummariser.Options.forPackage("dev.forge.lab.jvm"));
        assertThat(again).isEqualTo(again2);
    }

    @Test
    void toNanoTimeInterpolatesBetweenMarkers() {
        List<Marker> markers = List.of(new Marker("a", 10.0, 1_000_000_000L), new Marker("b", 20.0, 1_010_000_000L));
        assertThat(JfrSummariser.toNanoTime(10.0, markers)).isEqualTo(1_000_000_000L);
        assertThat(JfrSummariser.toNanoTime(15.0, markers)).isEqualTo(1_005_000_000L);
        assertThat(JfrSummariser.toNanoTime(20.0, markers)).isEqualTo(1_010_000_000L);
        // beyond the last marker: offset from it
        assertThat(JfrSummariser.toNanoTime(25.0, markers)).isEqualTo(1_015_000_000L);
        assertThat(JfrSummariser.toNanoTime(12.0, List.of(markers.getFirst()))).isEqualTo(1_002_000_000L);
    }

    @Test
    void arrayDescriptorsAreShownAsJavaTypes() {
        assertThat(JfrSummariser.readableType("[B")).isEqualTo("byte[]");
        assertThat(JfrSummariser.readableType("[[I")).isEqualTo("int[][]");
        assertThat(JfrSummariser.readableType("[Ljava.lang.Integer;")).isEqualTo("java.lang.Integer[]");
        assertThat(JfrSummariser.readableType("java.lang.String")).isEqualTo("java.lang.String");
        assertThat(summary.allocation().byClass()).anySatisfy(r -> assertThat(r.objectClass()).isEqualTo("byte[]"));
    }

    @Test
    void settingsDescribeSubMillisecondThresholds() {
        var s = JfrCapture.Settings.defaults().withLockThresholds(Duration.ofNanos(100_000), Duration.ofMillis(2));
        assertThat(s.describe()).containsEntry("jdk.JavaMonitorEnter#threshold", "100 us")
                .containsEntry("jdk.ThreadPark#threshold", "2 ms");
    }
}
