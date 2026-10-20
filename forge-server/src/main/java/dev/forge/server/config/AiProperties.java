package dev.forge.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Locale;

/**
 * AI Investigator provider configuration. Set only through the server's environment — never through
 * the API or the UI — so a request can neither change where evidence is sent nor read a key.
 *
 * @param provider  {@code none} (default: rule-based analysis only), {@code anthropic}, or {@code local}
 * @param model     model id; defaults per provider
 * @param apiKey    Anthropic API key (provider {@code anthropic}); never returned by any endpoint
 * @param localUrl  base URL of a local OpenAI-compatible server (provider {@code local}); must be loopback
 * @param baseUrl   optional override of the Anthropic API endpoint (an API gateway or proxy; tests use a local stub)
 */
@ConfigurationProperties("forge.ai")
public record AiProperties(String provider, String model, String apiKey, String localUrl, String baseUrl, int maxOutputTokens, int timeoutSeconds) {

    public static final String DEFAULT_ANTHROPIC_MODEL = "claude-opus-5-5";

    public AiProperties {
        provider = provider == null || provider.isBlank() ? "none" : provider.trim().toLowerCase(Locale.ROOT);
        if (!provider.equals("none") && !provider.equals("anthropic") && !provider.equals("local")) {
            throw new IllegalArgumentException("forge.ai.provider must be none, anthropic or local, got " + provider);
        }
        if (provider.equals("anthropic") && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalArgumentException("forge.ai.provider=anthropic needs an API key (FORGE_AI_API_KEY or ANTHROPIC_API_KEY)");
        }
        if (provider.equals("local")) {
            if (localUrl == null || localUrl.isBlank()) {
                throw new IllegalArgumentException("forge.ai.provider=local needs forge.ai.local-url");
            }
            String host = URI.create(localUrl).getHost();
            if (host == null || !(host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1") || host.equals("[::1]"))) {
                throw new IllegalArgumentException("forge.ai.local-url must point at a loopback address, got " + localUrl);
            }
        }
        if (model == null || model.isBlank()) {
            model = provider.equals("anthropic") ? DEFAULT_ANTHROPIC_MODEL : provider.equals("local") ? "llama3.1" : null;
        }
        baseUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl.trim();
        maxOutputTokens = maxOutputTokens <= 0 ? 16_000 : maxOutputTokens;
        timeoutSeconds = timeoutSeconds <= 0 ? 300 : timeoutSeconds;
    }

    public boolean enabled() {
        return !provider.equals("none");
    }

    /** Whether evidence leaves this machine when a question is asked. */
    public boolean sendsEvidenceOffHost() {
        return provider.equals("anthropic");
    }
}
