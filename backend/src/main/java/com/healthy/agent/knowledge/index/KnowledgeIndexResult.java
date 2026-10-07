package com.healthy.agent.knowledge.index;

import java.time.Instant;

public record KnowledgeIndexResult(
        String documentId,
        boolean indexed,
        int chunkCount,
        Instant indexedAt,
        boolean skipped
) {
}
