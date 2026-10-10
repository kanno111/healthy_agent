package com.healthy.agent.knowledge.evaluation;

import java.util.List;

public record KnowledgeRetrievalEvaluationDataset(
        String version,
        String description,
        List<KnowledgeRetrievalEvaluationCase> cases
) {
    public KnowledgeRetrievalEvaluationDataset {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
