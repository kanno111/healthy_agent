package com.healthy.agent.knowledge.index;

import java.util.List;

public record KnowledgeChunk(
        String chunkId,
        String documentId,
        String fileName,
        String contentType,
        int chunkIndex,
        String content,
        int contentLength,
        String documentSha256,
        String embeddingModel,
        List<Float> embedding,
        String indexedAt
) {
    public KnowledgeChunk {
        embedding = List.copyOf(embedding);
    }
}
