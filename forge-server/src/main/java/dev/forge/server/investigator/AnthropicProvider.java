package dev.forge.server.investigator;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;

import java.time.Duration;
import java.util.stream.Collectors;

/**
 * Claude through the official Anthropic Java SDK. Thinking is adaptive by default on the default
 * model; effort is set to {@code high} because answers are judged on reasoning over evidence. The
 * server-side refusal fallback is enabled, and a refusal is reported as such rather than as an answer.
 */
final class AnthropicProvider implements ModelProvider {

    private final AnthropicClient client;
    private final String model;
    private final long maxTokens;

    AnthropicProvider(String apiKey, String model, int maxTokens, int timeoutSeconds, String baseUrl) {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds));
        if (baseUrl != null) {
            b.baseUrl(baseUrl);   // tests point this at a local stub; production uses the default endpoint
        }
        this.client = b.build();
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public String name() {
        return "anthropic";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Answer ask(String system, String user) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(system)
                .addUserMessage(user)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.HIGH).build())
                // refusal fallback: route a declined request to a fallback model chosen by the server
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();
        Message m = client.messages().create(params);
        String stop = m.stopReason().map(Object::toString).orElse(null);
        if ("refusal".equalsIgnoreCase(stop)) {
            return new Answer("", stop, m.usage().inputTokens(), m.usage().outputTokens());
        }
        String text = m.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining("\n"));
        return new Answer(text, stop, m.usage().inputTokens(), m.usage().outputTokens());
    }
}
