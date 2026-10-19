package dev.forge.lab.performance;

import java.util.Arrays;

/**
 * A fixed amount of CPU-bound work: a chain of dependent xorshift steps the JIT cannot skip or
 * vectorise. The amount is a number of iterations, calibrated once to a nominal duration on this
 * machine; under CPU contention the same work takes longer, as real request processing would.
 */
public final class CpuWork {

    @SuppressWarnings("unused")
    private static volatile long sink;

    private CpuWork() {
    }

    public static long spin(long iterations) {
        long x = 0x9E3779B97F4A7C15L ^ iterations;
        for (long i = 0; i < iterations; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
        }
        sink = x;
        return x;
    }

    /** Iterations per microsecond: median of 7 timed batches after a JIT warm-up. */
    public static double calibrateIterationsPerMicro() {
        for (int i = 0; i < 30; i++) {
            spin(200_000);
        }
        int batches = 7;
        long iterations = 1_000_000;
        double[] perMicro = new double[batches];
        for (int i = 0; i < batches; i++) {
            long t0 = System.nanoTime();
            spin(iterations);
            long elapsed = Math.max(1, System.nanoTime() - t0);
            perMicro[i] = iterations / (elapsed / 1_000.0);
        }
        Arrays.sort(perMicro);
        return perMicro[batches / 2];
    }
}
