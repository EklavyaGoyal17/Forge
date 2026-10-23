package dev.forge.lab.stats;

import dev.forge.lab.scenario.MetricSpec.Direction;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class StatsTest {

    @Test
    void percentilesMatchNumpyLinearInterpolation() {
        // numpy.percentile([1..10], [50, 90, 95, 99]) == [5.5, 9.1, 9.55, 9.91]
        double[] v = {7, 1, 3, 10, 2, 9, 4, 8, 6, 5};
        Summary s = Summary.of(v);
        assertThat(s.n()).isEqualTo(10);
        assertThat(s.median()).isCloseTo(5.5, within(1e-12));
        assertThat(s.p90()).isCloseTo(9.1, within(1e-12));
        assertThat(s.p95()).isCloseTo(9.55, within(1e-12));
        assertThat(s.p99()).isCloseTo(9.91, within(1e-12));
        assertThat(s.mean()).isCloseTo(5.5, within(1e-12));
        // sample standard deviation of 1..10 = sqrt(55/6)
        assertThat(s.stddev()).isCloseTo(Math.sqrt(55.0 / 6.0), within(1e-12));
        assertThat(s.min()).isEqualTo(1);
        assertThat(s.max()).isEqualTo(10);
    }

    @Test
    void summaryOfSingleValueAndEmpty() {
        Summary one = Summary.of(new double[]{42});
        assertThat(one.median()).isEqualTo(42);
        assertThat(one.p99()).isEqualTo(42);
        assertThat(one.stddev()).isZero();
        assertThat(Summary.of(new double[0]).n()).isZero();
    }

    @Test
    void medianOfUnsortedSampleLeavesItUntouched() {
        double[] odd = {9, 1, 5};
        double[] even = {4, 1, 3, 2};
        assertThat(Summary.median(odd)).isEqualTo(5);
        assertThat(Summary.median(even)).isEqualTo(2.5);
        assertThat(odd).containsExactly(9, 1, 5);
        assertThat(Summary.median(new double[0])).isNaN();
    }

    @Test
    void normalCdfKnownValues() {
        assertThat(Normal.cdf(0)).isCloseTo(0.5, within(1e-7));
        assertThat(Normal.cdf(1.959964)).isCloseTo(0.975, within(1e-6));
        assertThat(Normal.cdf(-2.575829)).isCloseTo(0.005, within(1e-6));
    }

    @Test
    void mannWhitneyMatchesHandComputation() {
        // Pairs with a > b: (4.2 > 4.0), (5.5 > 4.0) → U = 2. No ties: mean U = 15, var U = 30.
        // z = -(|2 - 15| - 0.5) / sqrt(30) = -2.2822 → two-sided p = 2·(1 − Φ(2.2822)) ≈ 0.0225
        double[] a = {1.1, 2.3, 3.0, 4.2, 5.5};
        double[] b = {4.0, 6.1, 7.2, 8.8, 9.9, 10.5};
        MannWhitney.Result r = MannWhitney.test(a, b);
        assertThat(r.u()).isEqualTo(2.0);
        assertThat(r.z()).isCloseTo(-2.2822, within(1e-3));
        assertThat(r.pValue()).isCloseTo(0.0225, within(5e-4));
    }

    @Test
    void mannWhitneyIdenticalSamplesGivesPOne() {
        double[] a = {3, 3, 3, 3};
        assertThat(MannWhitney.test(a, a.clone()).pValue()).isEqualTo(1.0);
    }

    @Test
    void bootstrapIsDeterministicForSeedAndCoversTrueRatio() {
        SplittableRandom rnd = new SplittableRandom(7);
        double[] a = new double[40];
        double[] b = new double[40];
        for (int i = 0; i < 40; i++) {
            a[i] = 100 + rnd.nextGaussian() * 5;
            b[i] = 80 + rnd.nextGaussian() * 5;
        }
        Bootstrap.Interval i1 = Bootstrap.ratioOfMedians(a, b, 5_000, 0.95, 123);
        Bootstrap.Interval i2 = Bootstrap.ratioOfMedians(a, b, 5_000, 0.95, 123);
        assertThat(i1).isEqualTo(i2);
        assertThat(i1.low()).isLessThan(0.8);
        assertThat(i1.high()).isGreaterThan(0.8);
        assertThat(i1.high()).isLessThan(1.0);
    }

    @Test
    void comparisonVerdicts() {
        SplittableRandom rnd = new SplittableRandom(1);
        double[] base = new double[30];
        double[] faster = new double[30];
        double[] same = new double[30];
        for (int i = 0; i < 30; i++) {
            base[i] = 100 + rnd.nextGaussian() * 3;
            faster[i] = 70 + rnd.nextGaussian() * 3;
            same[i] = 100 + rnd.nextGaussian() * 3;
        }
        assertThat(Comparison.compare("t", Direction.LOWER_IS_BETTER, base, faster, 1).verdict())
                .isEqualTo(Comparison.Verdict.IMPROVEMENT);
        assertThat(Comparison.compare("t", Direction.HIGHER_IS_BETTER, base, faster, 1).verdict())
                .isEqualTo(Comparison.Verdict.REGRESSION);
        assertThat(Comparison.compare("t", Direction.LOWER_IS_BETTER, base, same, 1).verdict())
                .isEqualTo(Comparison.Verdict.NO_DIFFERENCE_DETECTED);
    }

    @Test
    void comparisonWarnsOnSmallSamplesAndHandlesConstants() {
        Comparison.Result small = Comparison.compare("t", Direction.LOWER_IS_BETTER,
                new double[]{1, 2, 3}, new double[]{1, 2, 3}, 1);
        assertThat(small.warnings()).anyMatch(w -> w.startsWith("Insufficient sample"));

        Comparison.Result zeros = Comparison.compare("lost", Direction.LOWER_IS_BETTER,
                new double[]{0, 0, 0, 0, 0}, new double[]{0, 0, 0, 0, 0}, 1);
        assertThat(zeros.verdict()).isEqualTo(Comparison.Verdict.IDENTICAL);

        Comparison.Result fromZero = Comparison.compare("lost", Direction.LOWER_IS_BETTER,
                new double[]{0, 0, 0, 0, 0}, new double[]{5, 6, 7, 8, 9}, 1);
        assertThat(fromZero.verdict()).isEqualTo(Comparison.Verdict.NOT_DIRECTIONAL);
    }
}
