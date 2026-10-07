package com.healthy.agent.knowledge.rag;

public record KnowledgeRagRequest(
        String question,
        Integer limit
) {
}
