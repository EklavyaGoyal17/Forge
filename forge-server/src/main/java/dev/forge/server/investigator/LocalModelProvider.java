package dev.forge.server.investigator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A model served on this machine through the OpenAI-compatible chat-completions endpoint that
 * Ollama, llama.cpp's server, vLLM and LM Studio expose. The base URL is restricted to loopback
 * ({@code AiProperties}), so evidence never leaves the host with this provider.
 */
final class LocalModelProvider implements ModelProvider {

    private final HttpClient http;
    private final URI endpoint;
    private final String model;
    private final int maxTokens;
    private final Duration timeout;
    private final JsonMapper json;

    LocalModelProvider(String baseUrl, String model, int maxTokens, int timeoutSeconds, JsonMapper json) {
        this.http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build();
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions");
        this.model = model;
        this.maxTokens = maxTokens;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.json = json;
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Answer ask(String system, String user) throws Exception {
        String body = json.writeValueAsString(Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user))));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) {
            throw new IllegalStateException("local model server answered HTTP " + r.statusCode() + ": "
                    + r.body().substring(0, Math.min(300, r.body().length())));
        }
        JsonNode root = json.readTree(r.body());
        JsonNode choice = root.path("choices").path(0);
        JsonNode usage = root.path("usage");
        return new Answer(choice.path("message").path("content").asString(""), choice.path("finish_reason").asString(null),
                usage.path("prompt_tokens").isNumber() ? usage.path("prompt_tokens").asLong() : null,
                usage.path("completion_tokens").isNumber() ? usage.path("completion_tokens").asLong() : null);
    }
}
