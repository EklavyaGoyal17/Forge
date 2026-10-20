package dev.forge.server.investigator;

/** A language model the investigator can ask. Implementations hold no state between questions. */
public interface ModelProvider {

    /** {@code anthropic} or {@code local}. */
    String name();

    String model();

    /**
     * @param stopReason as reported by the provider; {@code refusal} means the model declined and {@code text} is empty
     */
    record Answer(String text, String stopReason, Long inputTokens, Long outputTokens) {
    }

    Answer ask(String system, String user) throws Exception;
}
