package dev.forge.lab.scenarios.performance;

/** Test access to the package-private frame labelling. */
public final class WorkerSamplerAccess {

    private WorkerSamplerAccess() {
    }

    public static String topFrame(StackTraceElement[] stack) {
        return WorkerSampler.topFrame(stack);
    }
}
