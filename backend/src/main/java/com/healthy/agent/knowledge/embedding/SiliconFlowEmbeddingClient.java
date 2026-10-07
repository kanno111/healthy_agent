package com.healthy.agent.knowledge.embedding;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.config.EmbeddingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class SiliconFlowEmbeddingClient implements EmbeddingClient {
    private static final Logger log = LoggerFactory.getLogger(SiliconFlowEmbeddingClient.class);

    private final RestClient restClient;
    private final EmbeddingProperties properties;

    @Autowired
    public SiliconFlowEmbeddingClient(RestClient.Builder builder, EmbeddingProperties properties) {
        this(createRestClient(builder, properties), properties);
    }

    SiliconFlowEmbeddingClient(RestClient restClient, EmbeddingProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public EmbeddingBatch embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new EmbeddingBatch(properties.model(), List.of());
        }
        if (properties.apiKey().isBlank()) {
            log.warn("Embedding request skipped because the API key is not configured");
            throw new AgentException(AgentErrorCode.EMBEDDING_UNAVAILABLE);
        }
        if (texts.stream().anyMatch(text -> text == null || text.isBlank())) {
            throw new IllegalArgumentException("Embedding input must not contain blank text");
        }

        List<List<Float>> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += properties.batchSize()) {
            int end = Math.min(start + properties.batchSize(), texts.size());
            vectors.addAll(requestBatch(texts.subList(start, end)));
        }
        return new EmbeddingBatch(properties.model(), vectors);
    }

    private List<List<Float>> requestBatch(List<String> texts) {
        Map<String, Object> request = new HashMap<>();
        request.put("model", properties.model());
        request.put("input", texts);
        request.put("encoding_format", "float");

        try {
            EmbeddingResponse response = restClient.post()
                    .uri("/embeddings")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .body(request)
                    .retrieve()
                    .body(EmbeddingResponse.class);
            return validateResponse(response, texts.size());
        } catch (AgentException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            log.warn("Embedding provider returned HTTP {}", exception.getStatusCode().value());
            throw new AgentException(AgentErrorCode.EMBEDDING_UNAVAILABLE);
        } catch (ResourceAccessException exception) {
            log.warn("Embedding provider request failed: {}", exception.getClass().getSimpleName());
            throw new AgentException(AgentErrorCode.EMBEDDING_UNAVAILABLE);
        } catch (RuntimeException exception) {
            log.warn("Embedding response could not be processed: {}", exception.getClass().getSimpleName());
            throw new AgentException(AgentErrorCode.EMBEDDING_UNAVAILABLE);
        }
    }

    private List<List<Float>> validateResponse(EmbeddingResponse response, int expectedCount) {
        if (response == null || response.data() == null || response.data().size() != expectedCount) {
            throw new AgentException(AgentErrorCode.EMBEDDING_UNAVAILABLE);
        }

        List<List<Float>> ordered = new ArrayList<>(Collections.nCopies(expectedCount, null));
        for (EmbeddingItem item : response.data()) {
            if (item == null || item.index() < 0 || item.index() >= expectedCount
                    || ordered.get(item.index()) != null) {
                throw new AgentException(AgentErrorCode.EMBEDDING_UNAVAILABLE);
            }
            List<Float> vector = item.embedding();
            if (vector == null || vector.size() != properties.dimensions()
                    || vector.stream().anyMatch(value -> value == null || !Float.isFinite(value))) {
                throw new AgentException(AgentErrorCode.EMBEDDING_UNAVAILABLE);
            }
            ordered.set(item.index(), List.copyOf(vector));
        }
        if (ordered.stream().anyMatch(java.util.Objects::isNull)) {
            throw new AgentException(AgentErrorCode.EMBEDDING_UNAVAILABLE);
        }
        return List.copyOf(ordered);
    }

    private static RestClient createRestClient(RestClient.Builder builder, EmbeddingProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.toIntExact(properties.connectTimeout().toMillis()));
        requestFactory.setReadTimeout(Math.toIntExact(properties.readTimeout().toMillis()));
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    private record EmbeddingResponse(String model, List<EmbeddingItem> data) {
    }

    private record EmbeddingItem(int index, List<Float> embedding) {
    }
}
