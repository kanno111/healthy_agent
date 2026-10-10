package com.healthy.agent.knowledge.evaluation;

import java.util.List;

public record KnowledgeRetrievalEvaluationCase(
        String id,
        String question,
        List<String> expectedFileNames,
        boolean shouldAnswer,
        String category
) {
    public KnowledgeRetrievalEvaluationCase {
        expectedFileNames = expectedFileNames == null ? List.of() : List.copyOf(expectedFileNames);
    }
}
