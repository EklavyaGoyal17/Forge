package dev.forge.lab.stats;

import java.util.Arrays;

/**
 * Two-sided Mann–Whitney U test (Wilcoxon rank-sum) using the normal approximation with tie
 * correction and continuity correction.
 *
 * <p>Non-parametric: tests whether one sample tends to produce larger values than the other
 * without assuming normality. The normal approximation is reasonable for n ≥ ~8 per group; for
 * smaller samples the p-value is approximate and FORGE flags it.
 */
public final class MannWhitney {

    private MannWhitney() {
    }

    /** @param u U statistic for sample {@code a}; {@code probabilityAGreater} = U / (n1·n2). */
    public record Result(double u, double z, double pValue, double probabilityAGreater) {
    }

    public static Result test(double[] a, double[] b) {
        int n1 = a.length;
        int n2 = b.length;
        if (n1 == 0 || n2 == 0) {
            throw new IllegalArgumentException("both samples must be non-empty");
        }
        int n = n1 + n2;
        double[][] all = new double[n][2];
        for (int i = 0; i < n1; i++) {
            all[i][0] = a[i];
            all[i][1] = 0;
        }
        for (int i = 0; i < n2; i++) {
            all[n1 + i][0] = b[i];
            all[n1 + i][1] = 1;
        }
        Arrays.sort(all, (x, y) -> Double.compare(x[0], y[0]));

        double rankSumA = 0;
        double tieTerm = 0;
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && all[j + 1][0] == all[i][0]) {
                j++;
            }
            double avgRank = (i + j) / 2.0 + 1; // ranks are 1-based
            int ties = j - i + 1;
            if (ties > 1) {
                tieTerm += (double) ties * ties * ties - ties;
            }
            for (int k = i; k <= j; k++) {
                if (all[k][1] == 0) {
                    rankSumA += avgRank;
                }
            }
            i = j + 1;
        }

        double u = rankSumA - n1 * (n1 + 1) / 2.0;
        double meanU = n1 * (double) n2 / 2.0;
        double varU = n1 * (double) n2 / 12.0 * ((n + 1) - tieTerm / ((double) n * (n - 1)));
        if (varU <= 0) {
            // every value identical: no evidence of a difference
            return new Result(u, 0, 1.0, u / (n1 * (double) n2));
        }
        double diff = u - meanU;
        double corrected = Math.max(0, Math.abs(diff) - 0.5) * Math.signum(diff);
        double z = corrected / Math.sqrt(varU);
        double p = Math.min(1.0, 2 * (1 - Normal.cdf(Math.abs(z))));
        return new Result(u, z, p, u / (n1 * (double) n2));
    }
}
