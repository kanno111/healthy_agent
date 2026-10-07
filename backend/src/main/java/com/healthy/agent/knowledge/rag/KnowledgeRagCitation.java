package com.healthy.agent.knowledge.rag;

public record KnowledgeRagCitation(
        int reference,
        String chunkId,
        String documentId,
        String fileName,
        int chunkIndex,
        String content,
        double score
) {
}
