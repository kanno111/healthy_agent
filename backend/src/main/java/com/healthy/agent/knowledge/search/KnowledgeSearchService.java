package com.healthy.agent.knowledge.search;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.knowledge.embedding.EmbeddingBatch;
import com.healthy.agent.knowledge.embedding.EmbeddingClient;
import com.healthy.agent.knowledge.index.KnowledgeChunkRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KnowledgeSearchService {
    static final int DEFAULT_LIMIT = 5;
    static final int MAX_LIMIT = 20;
    static final int MAX_QUERY_CHARACTERS = 1000;

    private final EmbeddingClient embeddingClient;
    private final KnowledgeChunkRepository chunkRepository;

    public KnowledgeSearchService(
            EmbeddingClient embeddingClient,
            KnowledgeChunkRepository chunkRepository
    ) {
        this.embeddingClient = embeddingClient;
        this.chunkRepository = chunkRepository;
    }

    public KnowledgeSearchResponse search(KnowledgeSearchRequest request) {
        String query = normalizeQuery(request == null ? null : request.query());
        int limit = normalizeLimit(request == null ? null : request.limit());

        EmbeddingBatch embeddingBatch = embeddingClient.embed(List.of(query));
        if (embeddingBatch.vectors().size() != 1) {
            throw new AgentException(AgentErrorCode.EMBEDDING_UNAVAILABLE);
        }

        List<KnowledgeSearchHit> results = chunkRepository.search(
                embeddingBatch.vectors().getFirst(), limit);
        return new KnowledgeSearchResponse(query, embeddingBatch.model(), limit, results);
    }

    private String normalizeQuery(String query) {
        if (query == null) {
            throw new AgentException(AgentErrorCode.INVALID_SEARCH_QUERY);
        }
        String normalized = query.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_QUERY_CHARACTERS) {
            throw new AgentException(AgentErrorCode.INVALID_SEARCH_QUERY);
        }
        return normalized;
    }

    private int normalizeLimit(Integer limit) {
        int normalized = limit == null ? DEFAULT_LIMIT : limit;
        if (normalized < 1 || normalized > MAX_LIMIT) {
            throw new AgentException(AgentErrorCode.INVALID_SEARCH_LIMIT);
        }
        return normalized;
    }
}
