package com.healthy.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "agent.healthy-api")
public record HealthyApiProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout
) {
    public HealthyApiProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Healthy API base URL is required");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                || readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("Healthy API timeouts must be positive");
        }
        baseUrl = baseUrl.strip().replaceAll("/+$", "");
    }
}
