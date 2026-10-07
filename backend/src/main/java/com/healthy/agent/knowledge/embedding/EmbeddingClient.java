package com.healthy.agent.knowledge.embedding;

import java.util.List;

public interface EmbeddingClient {
    EmbeddingBatch embed(List<String> texts);
}
