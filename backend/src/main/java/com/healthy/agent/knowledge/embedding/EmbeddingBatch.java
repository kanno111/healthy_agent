package com.healthy.agent.knowledge.embedding;

import java.util.List;

public record EmbeddingBatch(
        String model,
        List<List<Float>> vectors
) {
    public EmbeddingBatch {
        vectors = vectors.stream().map(List::copyOf).toList();
    }
}
