package com.healthy.agent.knowledge;

import java.time.Instant;

public record KnowledgeDocumentMetadata(
        String id,
        String originalName,
        String objectKey,
        String contentType,
        long sizeBytes,
        String sha256,
        long uploaderId,
        boolean indexed,
        int chunkCount,
        Instant indexedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
