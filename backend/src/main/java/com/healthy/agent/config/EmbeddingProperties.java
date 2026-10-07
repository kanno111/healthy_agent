package com.healthy.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "agent.embedding")
public record EmbeddingProperties(
        String baseUrl,
        String apiKey,
        String model,
        int dimensions,
        int batchSize,
        Duration connectTimeout,
        Duration readTimeout
) {
    public EmbeddingProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Embedding base URL is required");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Embedding model is required");
        }
        if (dimensions < 1 || batchSize < 1) {
            throw new IllegalArgumentException("Embedding dimensions and batch size must be positive");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("Embedding timeouts must be positive");
        }
        baseUrl = baseUrl.strip().replaceAll("/+$", "");
        apiKey = apiKey == null ? "" : apiKey.strip();
        model = model.strip();
    }

    @Override
    public String toString() {
        return "EmbeddingProperties[baseUrl=" + baseUrl
                + ", apiKey=<redacted>, model=" + model
                + ", dimensions=" + dimensions
                + ", batchSize=" + batchSize
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
