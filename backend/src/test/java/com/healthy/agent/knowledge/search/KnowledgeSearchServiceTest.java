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
import static org.assertj.core.api.Assertions.within;
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
        when(chunkRepository.searchVector(vector, 5)).thenReturn(List.of(hit));

        KnowledgeSearchResponse response = service.search(
                new KnowledgeSearchRequest("  门诊预约怎么取消  ", null));

        assertThat(response.query()).isEqualTo("门诊预约怎么取消");
        assertThat(response.strategy()).isEqualTo(KnowledgeSearchStrategy.VECTOR);
        assertThat(response.embeddingModel()).isEqualTo("BAAI/bge-m3");
        assertThat(response.limit()).isEqualTo(5);
        assertThat(response.results()).containsExactly(hit);
    }

    @Test
    void acceptsConfiguredLimit() {
        List<Float> vector = Collections.nCopies(1024, 0.01f);
        when(embeddingClient.embed(List.of("退费规则")))
                .thenReturn(new EmbeddingBatch("BAAI/bge-m3", List.of(vector)));
        when(chunkRepository.searchVector(vector, 10)).thenReturn(List.of());

        assertThat(service.search(new KnowledgeSearchRequest("退费规则", 10)).limit()).isEqualTo(10);
        verify(chunkRepository).searchVector(vector, 10);
    }

    @Test
    void bm25SearchDoesNotCallEmbeddingProvider() {
        KnowledgeSearchHit hit = new KnowledgeSearchHit(
                1, "doc-2-0", "doc-2", "医保票据.md", "text/markdown",
                0, "电子票据通常在二十四小时内生成", 4.25d
        );
        when(chunkRepository.searchBm25("电子票据多久生成", 3)).thenReturn(List.of(hit));

        KnowledgeSearchResponse response = service.search(new KnowledgeSearchRequest(
                "电子票据多久生成", 3, KnowledgeSearchStrategy.BM25));

        assertThat(response.strategy()).isEqualTo(KnowledgeSearchStrategy.BM25);
        assertThat(response.embeddingModel()).isNull();
        assertThat(response.results()).containsExactly(hit);
        verify(chunkRepository).searchBm25("电子票据多久生成", 3);
        verifyNoInteractions(embeddingClient);
    }

    @Test
    void hybridSearchFusesBm25AndVectorRanksByChunkId() {
        List<Float> vector = Collections.nCopies(1024, 0.01f);
        KnowledgeSearchHit a = hit("a", "预约取消.md", 12.8d);
        KnowledgeSearchHit b = hit("b", "门诊缴费.md", 9.4d);
        KnowledgeSearchHit c = hit("c", "电子票据.md", 0.84d);
        KnowledgeSearchHit d = hit("d", "医保结算.md", 7.1d);
        when(embeddingClient.embed(List.of("预约退款多久到账")))
                .thenReturn(new EmbeddingBatch("BAAI/bge-m3", List.of(vector)));
        when(chunkRepository.searchBm25("预约退款多久到账", 50))
                .thenReturn(List.of(a, b, d));
        when(chunkRepository.searchVector(vector, 50))
                .thenReturn(List.of(b, c, d, a));

        KnowledgeSearchResponse response = service.search(new KnowledgeSearchRequest(
                "预约退款多久到账", 3, KnowledgeSearchStrategy.HYBRID));

        assertThat(response.strategy()).isEqualTo(KnowledgeSearchStrategy.HYBRID);
        assertThat(response.embeddingModel()).isEqualTo("BAAI/bge-m3");
        assertThat(response.results()).extracting(KnowledgeSearchHit::chunkId)
                .containsExactly("b", "a", "d");
        assertThat(response.results()).extracting(KnowledgeSearchHit::rank)
                .containsExactly(1, 2, 3);
        assertThat(response.results().getFirst().score()).isCloseTo(
                1.0d / 62.0d + 1.0d / 61.0d, within(0.0000001d));
        verify(chunkRepository).searchBm25("预约退款多久到账", 50);
        verify(chunkRepository).searchVector(vector, 50);
    }

    @Test
    void hybridSearchCountsDuplicateChunkOnlyOncePerBranch() {
        List<Float> vector = Collections.nCopies(1024, 0.01f);
        KnowledgeSearchHit a = hit("a", "预约取消.md", 12.8d);
        KnowledgeSearchHit b = hit("b", "门诊缴费.md", 9.4d);
        when(embeddingClient.embed(List.of("预约退款")))
                .thenReturn(new EmbeddingBatch("BAAI/bge-m3", List.of(vector)));
        when(chunkRepository.searchBm25("预约退款", 50)).thenReturn(List.of(a, a));
        when(chunkRepository.searchVector(vector, 50)).thenReturn(List.of(b));

        KnowledgeSearchResponse response = service.search(new KnowledgeSearchRequest(
                "预约退款", 5, KnowledgeSearchStrategy.HYBRID));

        assertThat(response.results()).extracting(KnowledgeSearchHit::chunkId)
                .containsExactly("a", "b");
        assertThat(response.results().getFirst().score()).isCloseTo(
                1.0d / 61.0d, within(0.0000001d));
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

    private KnowledgeSearchHit hit(String chunkId, String fileName, double score) {
        return new KnowledgeSearchHit(
                1, chunkId, "doc-" + chunkId, fileName, "text/markdown",
                0, fileName + "内容", score
        );
    }
}
