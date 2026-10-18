package dev.forge.server.run;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** One execution of one scenario variant in an isolated runner JVM. */
public record RunRecord(
        UUID id,
        UUID experimentId,
        int seq,
        String label,
        String role,
        String scenarioId,
        int scenarioVersion,
        String variant,
        JsonNode params,
        long seed,
        int trials,
        int warmupTrials,
        int heapMb,
        int timeoutSeconds,
        RunStatus status,
        String statusReason,
        Integer exitCode,
        Long progressDone,
        Long progressTotal,
        String progressPhase,
        JsonNode environment,
        JsonNode result,
        String forgeVersion,
        boolean cancelRequested,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt) {

    public Long durationMillis() {
        return startedAt == null || finishedAt == null ? null : finishedAt.toEpochMilli() - startedAt.toEpochMilli();
    }
}
