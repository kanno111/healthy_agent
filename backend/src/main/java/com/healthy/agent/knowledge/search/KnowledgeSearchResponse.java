package com.healthy.agent.knowledge.search;

import java.util.List;

public record KnowledgeSearchResponse(
        String query,
        String embeddingModel,
        int limit,
        List<KnowledgeSearchHit> results
) {
    public KnowledgeSearchResponse {
        results = List.copyOf(results);
    }
}
