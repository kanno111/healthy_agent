package com.healthy.agent.knowledge.evaluation;

import java.util.List;

public record KnowledgeRetrievalEvaluationCaseResult(
        String id,
        String category,
        String question,
        boolean shouldAnswer,
        List<String> expectedFileNames,
        List<String> returnedFileNames,
        int firstRelevantRank,
        boolean hitAtK,
        double recallAtK,
        double precisionAtK,
        double reciprocalRank,
        double ndcgAtK
) {
    public KnowledgeRetrievalEvaluationCaseResult {
        expectedFileNames = List.copyOf(expectedFileNames);
        returnedFileNames = List.copyOf(returnedFileNames);
    }
}
