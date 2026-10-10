package com.healthy.agent.knowledge.search;

import java.util.List;

public record KnowledgeSearchResponse(
        String query,
        KnowledgeSearchStrategy strategy,
        String embeddingModel,
        int limit,
        List<KnowledgeSearchHit> results
) {
    public KnowledgeSearchResponse {
        results = List.copyOf(results);
    }

    public KnowledgeSearchResponse(
            String query,
            String embeddingModel,
            int limit,
            List<KnowledgeSearchHit> results
    ) {
        this(query, KnowledgeSearchStrategy.VECTOR, embeddingModel, limit, results);
    }
}
