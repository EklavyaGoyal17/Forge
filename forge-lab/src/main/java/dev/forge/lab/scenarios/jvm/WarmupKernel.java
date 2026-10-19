package dev.forge.lab.scenarios.jvm;

/**
 * The code whose warm-up is measured by {@link WarmupScenario}.
 *
 * <p>Every trial defines a fresh copy of this class in a new class loader, so each trial starts
 * with no compiled code and no profile for these methods — as cold as the first call after JVM
 * start-up. It must stay self-contained: a call into another application class would reach code
 * that earlier trials already warmed up. (JDK methods such as {@code System.nanoTime} are shared
 * and stay warm; they are intrinsics or trivially small.)
 */
public final class WarmupKernel {

    private WarmupKernel() {
    }

    // forge:snippet kernel
    /** The measured computation: one pass of a mixing hash over the input. */
    public static long work(int[] data, int salt) {
        long h = 1125899906842597L ^ salt;
        for (int i = 0; i < data.length; i++) {
            int x = data[i] ^ salt;
            h = 31 * h + (x >>> 7) + (long) (x & 0xff) * i;
        }
        return h;
    }

    /** Times every invocation of work() from the very first one. */
    public static long measure(int[] data, long[] startNanos, long[] durationNanos) {
        long checksum = 0;
        for (int i = 0; i < durationNanos.length; i++) {
            long t0 = System.nanoTime();
            checksum += work(data, i);           // the result feeds the checksum, so it cannot be skipped
            long t1 = System.nanoTime();
            startNanos[i] = t0;
            durationNanos[i] = t1 - t0;
        }
        return checksum;
    }
    // forge:end
}
