package com.healthy.agent.knowledge.search;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.knowledge.embedding.EmbeddingBatch;
import com.healthy.agent.knowledge.embedding.EmbeddingClient;
import com.healthy.agent.knowledge.index.KnowledgeChunkRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class KnowledgeSearchService {
    static final int DEFAULT_LIMIT = 5;
    static final int MAX_LIMIT = 20;
    static final int MAX_QUERY_CHARACTERS = 1000;
    static final int RRF_RANK_CONSTANT = 60;
    static final int RRF_WINDOW_SIZE = 50;

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
        KnowledgeSearchStrategy strategy = normalizeStrategy(request == null ? null : request.strategy());

        if (strategy == KnowledgeSearchStrategy.BM25) {
            List<KnowledgeSearchHit> results = chunkRepository.searchBm25(query, limit);
            return new KnowledgeSearchResponse(query, strategy, null, limit, results);
        }

        EmbeddingBatch embeddingBatch = embedQuery(query);
        List<Float> queryVector = embeddingBatch.vectors().getFirst();
        if (strategy == KnowledgeSearchStrategy.HYBRID) {
            List<KnowledgeSearchHit> bm25Results = chunkRepository.searchBm25(
                    query, RRF_WINDOW_SIZE);
            List<KnowledgeSearchHit> vectorResults = chunkRepository.searchVector(
                    queryVector, RRF_WINDOW_SIZE);
            List<KnowledgeSearchHit> results = reciprocalRankFusion(
                    bm25Results, vectorResults, limit);
            return new KnowledgeSearchResponse(
                    query, strategy, embeddingBatch.model(), limit, results);
        }

        List<KnowledgeSearchHit> results = chunkRepository.searchVector(queryVector, limit);
        return new KnowledgeSearchResponse(
                query, strategy, embeddingBatch.model(), limit, results);
    }

    private EmbeddingBatch embedQuery(String query) {
        EmbeddingBatch embeddingBatch = embeddingClient.embed(List.of(query));
        if (embeddingBatch.vectors().size() != 1) {
            throw new AgentException(AgentErrorCode.EMBEDDING_UNAVAILABLE);
        }
        return embeddingBatch;
    }

    private List<KnowledgeSearchHit> reciprocalRankFusion(
            List<KnowledgeSearchHit> bm25Results,
            List<KnowledgeSearchHit> vectorResults,
            int limit
    ) {
        Map<String, KnowledgeSearchHit> hits = new LinkedHashMap<>();
        Map<String, Double> scores = new HashMap<>();
        Map<String, Integer> bestRanks = new HashMap<>();

        addRrfBranch(bm25Results, hits, scores, bestRanks);
        addRrfBranch(vectorResults, hits, scores, bestRanks);

        List<String> chunkIds = new ArrayList<>(hits.keySet());
        chunkIds.sort(Comparator
                .<String>comparingDouble(scores::get).reversed()
                .thenComparingInt(bestRanks::get)
                .thenComparing(chunkId -> chunkId));

        List<KnowledgeSearchHit> fused = new ArrayList<>();
        int resultCount = Math.min(limit, chunkIds.size());
        for (int index = 0; index < resultCount; index++) {
            String chunkId = chunkIds.get(index);
            KnowledgeSearchHit hit = hits.get(chunkId);
            fused.add(new KnowledgeSearchHit(
                    index + 1,
                    hit.chunkId(),
                    hit.documentId(),
                    hit.fileName(),
                    hit.contentType(),
                    hit.chunkIndex(),
                    hit.content(),
                    scores.get(chunkId)
            ));
        }
        return List.copyOf(fused);
    }

    private void addRrfBranch(
            List<KnowledgeSearchHit> branch,
            Map<String, KnowledgeSearchHit> hits,
            Map<String, Double> scores,
            Map<String, Integer> bestRanks
    ) {
        Set<String> seenChunkIds = new HashSet<>();
        for (int index = 0; index < branch.size(); index++) {
            KnowledgeSearchHit hit = branch.get(index);
            if (!seenChunkIds.add(hit.chunkId())) {
                continue;
            }
            int rank = index + 1;
            hits.putIfAbsent(hit.chunkId(), hit);
            scores.merge(hit.chunkId(), 1.0d / (RRF_RANK_CONSTANT + rank), Double::sum);
            bestRanks.merge(hit.chunkId(), rank, Math::min);
        }
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

    private KnowledgeSearchStrategy normalizeStrategy(KnowledgeSearchStrategy strategy) {
        return strategy == null ? KnowledgeSearchStrategy.VECTOR : strategy;
    }
}
