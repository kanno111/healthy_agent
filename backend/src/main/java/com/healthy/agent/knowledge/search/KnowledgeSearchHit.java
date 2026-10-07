package com.healthy.agent.knowledge.search;

public record KnowledgeSearchHit(
        int rank,
        String chunkId,
        String documentId,
        String fileName,
        String contentType,
        int chunkIndex,
        String content,
        double score
) {
}
