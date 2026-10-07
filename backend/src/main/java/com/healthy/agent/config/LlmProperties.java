package com.healthy.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "agent.llm")
public record LlmProperties(
        String baseUrl,
        String apiKey,
        String model,
        int maxOutputTokens,
        double temperature,
        Duration connectTimeout,
        Duration readTimeout
) {
    public LlmProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("LLM base URL is required");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("LLM model is required");
        }
        if (maxOutputTokens < 1) {
            throw new IllegalArgumentException("LLM max output tokens must be positive");
        }
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("LLM temperature must be between 0 and 2");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("LLM timeouts must be positive");
        }
        baseUrl = baseUrl.strip().replaceAll("/+$", "");
        apiKey = apiKey == null ? "" : apiKey.strip();
        model = model.strip();
    }

    @Override
    public String toString() {
        return "LlmProperties[baseUrl=" + baseUrl
                + ", apiKey=<redacted>, model=" + model
                + ", maxOutputTokens=" + maxOutputTokens
                + ", temperature=" + temperature
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
