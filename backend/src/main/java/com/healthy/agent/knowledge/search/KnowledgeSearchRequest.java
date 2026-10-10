package com.healthy.agent.knowledge.search;

public record KnowledgeSearchRequest(
        String query,
        Integer limit,
        KnowledgeSearchStrategy strategy
) {
    public KnowledgeSearchRequest(String query, Integer limit) {
        this(query, limit, null);
    }
}
