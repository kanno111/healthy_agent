package com.healthy.agent.knowledge.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.json.JsonData;
import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.config.ElasticsearchProperties;
import com.healthy.agent.config.EmbeddingProperties;
import com.healthy.agent.knowledge.search.KnowledgeSearchHit;
import jakarta.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.ArrayList;

@Repository
public class KnowledgeChunkRepository {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeChunkRepository.class);

    private final ElasticsearchClient client;
    private final String indexName;
    private final int embeddingDimensions;
    private volatile boolean indexReady;

    public KnowledgeChunkRepository(
            ElasticsearchClient client,
            ElasticsearchProperties properties,
            EmbeddingProperties embeddingProperties
    ) {
        this.client = client;
        this.indexName = properties.indexName();
        this.embeddingDimensions = embeddingProperties.dimensions();
    }

    public void replaceDocumentChunks(String documentId, List<KnowledgeChunk> chunks) {
        try {
            ensureIndex();
            deleteInternal(documentId);

            BulkRequest.Builder request = new BulkRequest.Builder().refresh(Refresh.WaitFor);
            for (KnowledgeChunk chunk : chunks) {
                request.operations(operation -> operation.index(index -> index
                        .index(indexName)
                        .id(chunk.chunkId())
                        .document(chunk)
                ));
            }
            BulkResponse response = client.bulk(request.build());
            if (response.errors()) {
                String reason = response.items().stream()
                        .filter(item -> item.error() != null)
                        .map(item -> item.error().reason())
                        .findFirst()
                        .orElse("unknown bulk indexing failure");
                throw new IllegalStateException(reason);
            }
        } catch (Exception exception) {
            cleanupAfterFailure(documentId);
            log.warn("Failed to replace Elasticsearch chunks for document {}: {}: {}",
                    documentId, exception.getClass().getSimpleName(), exception.getMessage());
            throw new AgentException(AgentErrorCode.INDEX_UNAVAILABLE);
        }
    }

    public void deleteByDocumentId(String documentId) {
        try {
            ensureIndex();
            deleteInternal(documentId);
        } catch (Exception exception) {
            log.warn("Failed to delete Elasticsearch chunks for document {}: {}",
                    documentId, exception.getClass().getSimpleName());
            throw new AgentException(AgentErrorCode.INDEX_UNAVAILABLE);
        }
    }

    public List<KnowledgeSearchHit> searchVector(List<Float> queryVector, int limit) {
        try {
            ensureIndex();
            int candidates = Math.max(50, limit * 10);
            var response = client.search(request -> request
                            .index(indexName)
                            .size(limit)
                            .source(source -> source.filter(filter -> filter
                                    .excludes("embedding", "documentSha256", "embeddingModel", "indexedAt")))
                            .knn(knn -> knn
                                    .field("embedding")
                                    .queryVector(queryVector)
                                    .k(limit)
                                    .numCandidates(candidates)),
                    JsonData.class);

            List<KnowledgeSearchHit> results = new ArrayList<>();
            int rank = 1;
            for (var hit : response.hits().hits()) {
                JsonData sourceData = hit.source();
                if (sourceData == null) {
                    continue;
                }
                JsonObject source = sourceData.toJson().asJsonObject();
                results.add(new KnowledgeSearchHit(
                        rank++, source.getString("chunkId"), source.getString("documentId"),
                        source.getString("fileName"), source.getString("contentType"),
                        source.getInt("chunkIndex"), source.getString("content"),
                        hit.score() == null ? 0.0d : hit.score()
                ));
            }
            return List.copyOf(results);
        } catch (Exception exception) {
            log.warn("Failed to search Elasticsearch knowledge chunks: {}: {}",
                    exception.getClass().getSimpleName(), exception.getMessage());
            throw new AgentException(AgentErrorCode.INDEX_UNAVAILABLE);
        }
    }

    public List<KnowledgeSearchHit> searchBm25(String query, int limit) {
        try {
            ensureIndex();
            var response = client.search(request -> request
                            .index(indexName)
                            .size(limit)
                            .source(source -> source.filter(filter -> filter
                                    .excludes("embedding", "documentSha256", "embeddingModel", "indexedAt")))
                            .query(searchQuery -> searchQuery.match(match -> match
                                    .field("content")
                                    .query(query))),
                    JsonData.class);

            List<KnowledgeSearchHit> results = new ArrayList<>();
            int rank = 1;
            for (var hit : response.hits().hits()) {
                JsonData sourceData = hit.source();
                if (sourceData == null) {
                    continue;
                }
                JsonObject source = sourceData.toJson().asJsonObject();
                results.add(new KnowledgeSearchHit(
                        rank++, source.getString("chunkId"), source.getString("documentId"),
                        source.getString("fileName"), source.getString("contentType"),
                        source.getInt("chunkIndex"), source.getString("content"),
                        hit.score() == null ? 0.0d : hit.score()
                ));
            }
            return List.copyOf(results);
        } catch (Exception exception) {
            log.warn("Failed to BM25 search Elasticsearch knowledge chunks: {}: {}",
                    exception.getClass().getSimpleName(), exception.getMessage());
            throw new AgentException(AgentErrorCode.INDEX_UNAVAILABLE);
        }
    }

    private synchronized void ensureIndex() throws Exception {
        if (indexReady) {
            return;
        }
        boolean exists = client.indices().exists(request -> request.index(indexName)).value();
        if (!exists) {
            client.indices().create(request -> request
                    .index(indexName)
                    .settings(settings -> settings
                            .numberOfShards("1")
                            .numberOfReplicas("0"))
                    .mappings(mappings -> mappings
                            .properties("chunkId", property -> property.keyword(keyword -> keyword))
                            .properties("documentId", property -> property.keyword(keyword -> keyword))
                            .properties("fileName", property -> property.keyword(keyword -> keyword))
                            .properties("contentType", property -> property.keyword(keyword -> keyword))
                            .properties("chunkIndex", property -> property.integer(integer -> integer))
                            .properties("content", property -> property.text(text -> text))
                            .properties("contentLength", property -> property.integer(integer -> integer))
                            .properties("documentSha256", property -> property.keyword(keyword -> keyword))
                            .properties("embeddingModel", property -> property.keyword(keyword -> keyword))
                            .properties("embedding", property -> property.denseVector(vector -> vector
                                    .dims(embeddingDimensions)
                                    .index(true)
                                    .similarity(DenseVectorSimilarity.Cosine)))
                            .properties("indexedAt", property -> property.date(date -> date))
                    ));
        } else {
            client.indices().putMapping(request -> request
                    .index(indexName)
                    .properties("embeddingModel", property -> property.keyword(keyword -> keyword))
                    .properties("embedding", property -> property.denseVector(vector -> vector
                            .dims(embeddingDimensions)
                            .index(true)
                            .similarity(DenseVectorSimilarity.Cosine))));
        }
        indexReady = true;
    }

    private void deleteInternal(String documentId) throws Exception {
        client.deleteByQuery(request -> request
                .index(indexName)
                .conflicts(Conflicts.Proceed)
                .refresh(true)
                .query(query -> query.term(term -> term
                        .field("documentId")
                        .value(documentId))));
    }

    private void cleanupAfterFailure(String documentId) {
        try {
            if (indexReady) {
                deleteInternal(documentId);
            }
        } catch (Exception cleanupException) {
            log.error("Failed to clean partial Elasticsearch chunks for document {}: {}",
                    documentId, cleanupException.getClass().getSimpleName());
        }
    }
}
