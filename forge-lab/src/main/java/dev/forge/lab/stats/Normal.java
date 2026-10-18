package dev.forge.lab.stats;

/** Standard normal distribution helpers. */
final class Normal {

    private Normal() {
    }

    static double cdf(double x) {
        return 0.5 * erfc(-x / Math.sqrt(2));
    }

    /**
     * Complementary error function, Chebyshev approximation from Numerical Recipes (3rd ed., §6.2),
     * fractional error below 1.2e-7 everywhere — ample for reporting p-values to 3 decimals.
     */
    static double erfc(double x) {
        double z = Math.abs(x);
        double t = 1.0 / (1.0 + 0.5 * z);
        double ans = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
                + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? ans : 2.0 - ans;
    }
}
