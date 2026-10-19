package dev.forge.lab.scenarios.jvm;

import dev.forge.lab.jvm.JfrCapture;
import dev.forge.lab.jvm.JfrSummariser;
import dev.forge.lab.jvm.JfrSummary;
import dev.forge.lab.scenario.RunContext;
import dev.forge.lab.scenario.VariantSpec;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/** Helpers shared by the JVM Lab scenarios. */
final class JvmLab {

    /** Frames in this package are "application code" in the JFR tables. */
    static final String PACKAGE = "dev.forge.lab.scenarios.jvm";

    private JvmLab() {
    }

    /**
     * A variant that declares JVM flags is only meaningful in a JVM started with them. The FORGE
     * launcher adds them; anything else (an IDE, a unit test) must not silently produce numbers for
     * the wrong JIT mode.
     */
    static void requireJvmFlags(VariantSpec variant) {
        List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (String flag : variant.jvmFlags()) {
            if (!args.contains(flag)) {
                throw new IllegalStateException("variant '" + variant.id() + "' must run in a JVM started with " + flag
                        + " (the FORGE launcher adds it); this JVM was started with " + args);
            }
        }
    }

    /** What the JIT is doing in this JVM, as far as the JVM reports it. */
    static Map<String, Object> jitMode(VariantSpec variant) {
        Map<String, Object> m = new LinkedHashMap<>();
        var jit = ManagementFactory.getCompilationMXBean();
        m.put("variantFlags", variant.jvmFlags());
        m.put("compiler", jit == null ? null : jit.getName());
        m.put("jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(a -> a.startsWith("-X")).toList());
        return m;
    }

    static int[] data(long seed, int n, int bound) {
        SplittableRandom r = new SplittableRandom(seed);
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = r.nextInt(bound);
        }
        return a;
    }

    static JfrSummary stopAndSummarise(JfrCapture capture, RunContext ctx, JfrSummariser.Options options)
            throws IOException {
        var file = capture.stop();
        long t0 = System.nanoTime();
        JfrSummary s = JfrSummariser.summarise(file, capture.startedAt(), capture.settings(), options);
        ctx.info(String.format("JFR: %,d bytes recorded in %.0f ms, summarised in %.0f ms (raw file stays in the run directory)",
                s.recording().fileBytes(), s.recording().durationMs(), (System.nanoTime() - t0) / 1e6));
        return s;
    }

    static double median(long[] values, int from, int to) {
        long[] copy = Arrays.copyOfRange(values, from, to);
        Arrays.sort(copy);
        int n = copy.length;
        return n % 2 == 1 ? copy[n / 2] : (copy[n / 2 - 1] + copy[n / 2]) / 2.0;
    }

    static long threadAllocatedBytes() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }
}
