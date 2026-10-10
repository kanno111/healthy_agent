package com.healthy.agent.knowledge.evaluation;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.knowledge.search.KnowledgeSearchHit;
import com.healthy.agent.knowledge.search.KnowledgeSearchRequest;
import com.healthy.agent.knowledge.search.KnowledgeSearchResponse;
import com.healthy.agent.knowledge.search.KnowledgeSearchService;
import com.healthy.agent.knowledge.search.KnowledgeSearchStrategy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalEvaluationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-09T02:00:00Z");

    @Test
    void calculatesDocumentLevelMetricsAndNegativeEmptyRate() {
        KnowledgeSearchService searchService = mock(KnowledgeSearchService.class);
        KnowledgeRetrievalEvaluationDatasetLoader loader = mock(
                KnowledgeRetrievalEvaluationDatasetLoader.class);
        when(loader.get()).thenReturn(new KnowledgeRetrievalEvaluationDataset(
                "test-v1", "test", List.of(
                new KnowledgeRetrievalEvaluationCase(
                        "q1", "问题一", List.of("a.md"), true, "分类一"),
                new KnowledgeRetrievalEvaluationCase(
                        "q2", "问题二", List.of("b.md", "c.md"), true, "分类二"),
                new KnowledgeRetrievalEvaluationCase(
                        "q3", "负例", List.of(), false, "负例")
        )));
        when(searchService.search(new KnowledgeSearchRequest(
                "问题一", 5, KnowledgeSearchStrategy.BM25))).thenReturn(response(
                hit(1, "x.md"), hit(2, "a.md")));
        when(searchService.search(new KnowledgeSearchRequest(
                "问题二", 5, KnowledgeSearchStrategy.BM25))).thenReturn(response(
                hit(1, "b.md"), hit(2, "c.md")));
        when(searchService.search(new KnowledgeSearchRequest(
                "负例", 5, KnowledgeSearchStrategy.BM25))).thenReturn(response());

        KnowledgeRetrievalEvaluationService service = new KnowledgeRetrievalEvaluationService(
                searchService, loader, Clock.fixed(NOW, ZoneOffset.UTC));
        KnowledgeRetrievalEvaluationResponse result = service.evaluate(
                new KnowledgeRetrievalEvaluationRequest(KnowledgeSearchStrategy.BM25, 5));

        assertThat(result.datasetVersion()).isEqualTo("test-v1");
        assertThat(result.totalCases()).isEqualTo(3);
        assertThat(result.positiveCases()).isEqualTo(2);
        assertThat(result.negativeCases()).isEqualTo(1);
        assertThat(result.recallAtK()).isEqualTo(1.0d);
        assertThat(result.precisionAtK()).isCloseTo(0.3d, within(0.000001d));
        assertThat(result.hitRateAtK()).isEqualTo(1.0d);
        assertThat(result.mrr()).isEqualTo(0.75d);
        assertThat(result.ndcgAtK()).isBetween(0.81d, 0.82d);
        assertThat(result.negativeEmptyRate()).isEqualTo(1.0d);
        assertThat(result.evaluatedAt()).isEqualTo(NOW);
        assertThat(result.cases().getFirst().firstRelevantRank()).isEqualTo(2);
        assertThat(result.cases().getFirst().hitAtK()).isTrue();
    }

    @Test
    void rejectsUnsupportedTopK() {
        KnowledgeRetrievalEvaluationService service = new KnowledgeRetrievalEvaluationService(
                mock(KnowledgeSearchService.class),
                mock(KnowledgeRetrievalEvaluationDatasetLoader.class),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.evaluate(
                new KnowledgeRetrievalEvaluationRequest(KnowledgeSearchStrategy.BM25, 21)))
                .isInstanceOfSatisfying(AgentException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(AgentErrorCode.INVALID_SEARCH_LIMIT));
    }

    @Test
    void calculatesHitRateFromQueriesWithAtLeastOneRelevantResult() {
        KnowledgeSearchService searchService = mock(KnowledgeSearchService.class);
        KnowledgeRetrievalEvaluationDatasetLoader loader = mock(
                KnowledgeRetrievalEvaluationDatasetLoader.class);
        when(loader.get()).thenReturn(new KnowledgeRetrievalEvaluationDataset(
                "test-v2", "test", List.of(
                new KnowledgeRetrievalEvaluationCase(
                        "q1", "命中问题", List.of("a.md"), true, "分类"),
                new KnowledgeRetrievalEvaluationCase(
                        "q2", "未命中问题", List.of("b.md"), true, "分类")
        )));
        when(searchService.search(new KnowledgeSearchRequest(
                "命中问题", 5, KnowledgeSearchStrategy.BM25))).thenReturn(response(hit(1, "a.md")));
        when(searchService.search(new KnowledgeSearchRequest(
                "未命中问题", 5, KnowledgeSearchStrategy.BM25))).thenReturn(response(hit(1, "x.md")));

        KnowledgeRetrievalEvaluationService service = new KnowledgeRetrievalEvaluationService(
                searchService, loader, Clock.fixed(NOW, ZoneOffset.UTC));
        KnowledgeRetrievalEvaluationResponse result = service.evaluate(
                new KnowledgeRetrievalEvaluationRequest(KnowledgeSearchStrategy.BM25, 5));

        assertThat(result.hitRateAtK()).isEqualTo(0.5d);
        assertThat(result.cases()).extracting(KnowledgeRetrievalEvaluationCaseResult::hitAtK)
                .containsExactly(true, false);
    }

    private KnowledgeSearchResponse response(KnowledgeSearchHit... hits) {
        return new KnowledgeSearchResponse(
                "query", KnowledgeSearchStrategy.BM25, null, 5, List.of(hits));
    }

    private KnowledgeSearchHit hit(int rank, String fileName) {
        return new KnowledgeSearchHit(
                rank, fileName + "-0", fileName, fileName, "text/markdown",
                0, "content", 1.0d);
    }
}
