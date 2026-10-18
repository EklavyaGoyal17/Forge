package dev.forge.lab.scenarios.algorithms;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark: membership tests against a collection of {@code size} distinct ints, using a
 * fixed batch of {@link #QUERIES} pre-generated queries of which {@code hitPercent}% are present.
 *
 * <p>Scores are nanoseconds per single lookup ({@link OperationsPerInvocation}). Every benchmark
 * returns the hit count so JMH consumes it and the JIT cannot eliminate the work as dead code.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(LookupBenchmark.QUERIES)
public class LookupBenchmark {

    public static final int QUERIES = 1024;

    @Param("1000")
    public int size;

    @Param("50")
    public int hitPercent;

    @Param("42")
    public long seed;

    int[] unsorted;
    int[] sorted;
    List<Integer> list;
    Set<Integer> set;
    int[] queries;

    @Setup(Level.Trial)
    public void setup() {
        Data d = Data.generate(size, hitPercent, seed);
        unsorted = d.unsorted();
        sorted = d.unsorted().clone();
        Arrays.sort(sorted);
        list = new ArrayList<>(size);
        set = new HashSet<>(Math.max(16, (int) (size / 0.75f) + 1));
        for (int v : unsorted) {
            list.add(v);
            set.add(v);
        }
        queries = d.queries();
    }

    /** Elements are the even numbers 0, 2, …, 2(size−1) in shuffled order; misses are odd numbers. */
    record Data(int[] unsorted, int[] queries) {
        static Data generate(int size, int hitPercent, long seed) {
            SplittableRandom rnd = new SplittableRandom(seed);
            int[] values = new int[size];
            for (int i = 0; i < size; i++) {
                values[i] = 2 * i;
            }
            for (int i = size - 1; i > 0; i--) {         // Fisher–Yates shuffle
                int j = rnd.nextInt(i + 1);
                int tmp = values[i];
                values[i] = values[j];
                values[j] = tmp;
            }
            int[] q = new int[QUERIES];
            for (int i = 0; i < QUERIES; i++) {
                boolean hit = rnd.nextInt(100) < hitPercent;
                q[i] = hit ? values[rnd.nextInt(size)] : 2 * rnd.nextInt(size) + 1;
            }
            return new Data(values, q);
        }
    }

    // forge:snippet linear-array
    @Benchmark
    public int linearArray() {
        int hits = 0;
        for (int q : queries) {
            for (int v : unsorted) {          // O(n) per lookup, but sequential primitive reads
                if (v == q) {
                    hits++;
                    break;
                }
            }
        }
        return hits;
    }
    // forge:end

    // forge:snippet linear-list
    @Benchmark
    public int linearList() {
        int hits = 0;
        for (int q : queries) {
            if (list.contains(q)) {           // O(n), boxes q and calls Integer.equals per element
                hits++;
            }
        }
        return hits;
    }
    // forge:end

    // forge:snippet hash-set
    @Benchmark
    public int hashSet() {
        int hits = 0;
        for (int q : queries) {
            if (set.contains(q)) {            // O(1) expected: hash, index, compare a few nodes
                hits++;
            }
        }
        return hits;
    }
    // forge:end

    // forge:snippet binary-search
    @Benchmark
    public int binarySearch() {
        int hits = 0;
        for (int q : queries) {
            if (Arrays.binarySearch(sorted, q) >= 0) {   // O(log n), branchy, cache-unfriendly at large n
                hits++;
            }
        }
        return hits;
    }
    // forge:end
}
