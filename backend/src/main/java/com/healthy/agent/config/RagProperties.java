package com.healthy.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.rag")
public record RagProperties(
        int defaultTopK,
        int maxTopK,
        double minScore,
        int maxContextCharacters
) {
    public RagProperties {
        if (defaultTopK < 1 || maxTopK < defaultTopK) {
            throw new IllegalArgumentException("RAG top-k settings are invalid");
        }
        if (!Double.isFinite(minScore) || minScore < 0) {
            throw new IllegalArgumentException("RAG minimum score must be non-negative");
        }
        if (maxContextCharacters < 1) {
            throw new IllegalArgumentException("RAG context limit must be positive");
        }
    }
}
