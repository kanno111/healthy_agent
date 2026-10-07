package com.healthy.agent.knowledge;

import java.time.Instant;

public record KnowledgeDocument(
        String id,
        String fileName,
        long size,
        String contentType,
        String status,
        boolean indexed,
        int chunkCount,
        Instant indexedAt,
        Instant uploadedAt
) {
}
