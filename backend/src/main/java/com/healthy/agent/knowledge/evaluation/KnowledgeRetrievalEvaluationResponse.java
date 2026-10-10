package com.healthy.agent.knowledge.evaluation;

import com.healthy.agent.knowledge.search.KnowledgeSearchStrategy;

import java.time.Instant;
import java.util.List;

public record KnowledgeRetrievalEvaluationResponse(
        String datasetVersion,
        KnowledgeSearchStrategy strategy,
        int limit,
        int totalCases,
        int positiveCases,
        int negativeCases,
        double recallAtK,
        double precisionAtK,
        double hitRateAtK,
        double mrr,
        double ndcgAtK,
        double negativeEmptyRate,
        Instant evaluatedAt,
        List<KnowledgeRetrievalEvaluationCaseResult> cases
) {
    public KnowledgeRetrievalEvaluationResponse {
        cases = List.copyOf(cases);
    }
}
