package dev.forge.server.experiment;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** An engineering investigation: one question, one hypothesis, any number of runs. */
public record Experiment(
        UUID id,
        String title,
        String question,
        String hypothesis,
        Instant hypothesisUpdatedAt,
        String scenarioId,
        String status,
        String observations,
        String alternativeExplanations,
        String decision,
        String followUps,
        JsonNode reviewAnswers,
        JsonNode tags,
        Instant createdAt,
        Instant updatedAt) {
}
