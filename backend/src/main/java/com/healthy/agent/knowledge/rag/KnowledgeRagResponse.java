package com.healthy.agent.knowledge.rag;

import com.healthy.agent.llm.TokenUsage;

import java.util.List;

public record KnowledgeRagResponse(
        String question,
        String answer,
        String model,
        String embeddingModel,
        List<KnowledgeRagCitation> citations,
        TokenUsage usage
) {
    public KnowledgeRagResponse {
        citations = List.copyOf(citations);
    }
}
