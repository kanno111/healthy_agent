package com.healthy.agent.knowledge.evaluation;

import com.healthy.agent.knowledge.search.KnowledgeSearchStrategy;

public record KnowledgeRetrievalEvaluationRequest(
        KnowledgeSearchStrategy strategy,
        Integer limit
) {
}
