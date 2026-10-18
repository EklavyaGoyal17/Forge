package dev.forge.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits and locations for the isolated runner JVM. Every limit here is a ceiling that user input
 * cannot exceed; see docs/SECURITY.md.
 */
@ConfigurationProperties("forge.runner")
public record RunnerProperties(
        String classpath,
        String javaExecutable,
        String workRoot,
        int maxConcurrentRuns,
        int queueCapacity,
        int defaultHeapMb,
        int maxHeapMb,
        int maxTimeoutSeconds,
        int activeProcessorCount,
        long stdoutCapBytes,
        int stderrCapBytes,
        int artifactCapBytes,
        int maxArtifactsPerRun,
        boolean keepWorkDirs) {

    public RunnerProperties {
        if (maxConcurrentRuns < 1) {
            throw new IllegalArgumentException("forge.runner.max-concurrent-runs must be >= 1");
        }
        if (defaultHeapMb < 32 || defaultHeapMb > maxHeapMb) {
            throw new IllegalArgumentException("forge.runner.default-heap-mb must be in [32, max-heap-mb]");
        }
    }
}
