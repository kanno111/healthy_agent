package com.healthy.agent.knowledge.search;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.knowledge.embedding.EmbeddingBatch;
import com.healthy.agent.knowledge.embedding.EmbeddingClient;
import com.healthy.agent.knowledge.index.KnowledgeChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeSearchServiceTest {
    private EmbeddingClient embeddingClient;
    private KnowledgeChunkRepository chunkRepository;
    private KnowledgeSearchService service;

    @BeforeEach
    void setUp() {
        embeddingClient = mock(EmbeddingClient.class);
        chunkRepository = mock(KnowledgeChunkRepository.class);
        service = new KnowledgeSearchService(embeddingClient, chunkRepository);
    }

    @Test
    void embedsNormalizedQueryAndReturnsRankedChunks() {
        List<Float> vector = Collections.nCopies(1024, 0.01f);
        KnowledgeSearchHit hit = new KnowledgeSearchHit(
                1, "doc-1-0", "doc-1", "预约规则.md", "text/markdown",
                0, "预约取消须知", 0.91d
        );
        when(embeddingClient.embed(List.of("门诊预约怎么取消")))
                .thenReturn(new EmbeddingBatch("BAAI/bge-m3", List.of(vector)));
        when(chunkRepository.search(vector, 5)).thenReturn(List.of(hit));

        KnowledgeSearchResponse response = service.search(
                new KnowledgeSearchRequest("  门诊预约怎么取消  ", null));

        assertThat(response.query()).isEqualTo("门诊预约怎么取消");
        assertThat(response.embeddingModel()).isEqualTo("BAAI/bge-m3");
        assertThat(response.limit()).isEqualTo(5);
        assertThat(response.results()).containsExactly(hit);
    }

    @Test
    void acceptsConfiguredLimit() {
        List<Float> vector = Collections.nCopies(1024, 0.01f);
        when(embeddingClient.embed(List.of("退费规则")))
                .thenReturn(new EmbeddingBatch("BAAI/bge-m3", List.of(vector)));
        when(chunkRepository.search(vector, 10)).thenReturn(List.of());

        assertThat(service.search(new KnowledgeSearchRequest("退费规则", 10)).limit()).isEqualTo(10);
        verify(chunkRepository).search(vector, 10);
    }

    @Test
    void rejectsBlankQueryBeforeCallingDependencies() {
        assertThatThrownBy(() -> service.search(new KnowledgeSearchRequest("  ", 5)))
                .isInstanceOfSatisfying(AgentException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(AgentErrorCode.INVALID_SEARCH_QUERY));
        verifyNoInteractions(embeddingClient, chunkRepository);
    }

    @Test
    void rejectsLimitOutsideSupportedRange() {
        assertThatThrownBy(() -> service.search(new KnowledgeSearchRequest("预约规则", 21)))
                .isInstanceOfSatisfying(AgentException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(AgentErrorCode.INVALID_SEARCH_LIMIT));
        verifyNoInteractions(embeddingClient, chunkRepository);
    }
}
