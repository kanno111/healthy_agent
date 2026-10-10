package com.healthy.agent.knowledge.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

@Component
public class KnowledgeRetrievalEvaluationDatasetLoader {
    static final String DATASET_PATH = "evaluation/rag-evaluation-v3.json";

    private final KnowledgeRetrievalEvaluationDataset dataset;

    public KnowledgeRetrievalEvaluationDatasetLoader(ObjectMapper objectMapper) {
        this.dataset = load(objectMapper);
    }

    public KnowledgeRetrievalEvaluationDataset get() {
        return dataset;
    }

    private KnowledgeRetrievalEvaluationDataset load(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource(DATASET_PATH).getInputStream()) {
            KnowledgeRetrievalEvaluationDataset loaded = objectMapper.readValue(
                    input, KnowledgeRetrievalEvaluationDataset.class);
            validate(loaded);
            return loaded;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load RAG evaluation dataset", exception);
        }
    }

    private void validate(KnowledgeRetrievalEvaluationDataset loaded) {
        if (loaded.version() == null || loaded.version().isBlank() || loaded.cases().isEmpty()) {
            throw new IllegalStateException("RAG evaluation dataset is empty");
        }
        Set<String> ids = new HashSet<>();
        for (KnowledgeRetrievalEvaluationCase evaluationCase : loaded.cases()) {
            if (evaluationCase.id() == null || evaluationCase.id().isBlank()
                    || evaluationCase.question() == null || evaluationCase.question().isBlank()
                    || evaluationCase.category() == null || evaluationCase.category().isBlank()
                    || !ids.add(evaluationCase.id())) {
                throw new IllegalStateException("RAG evaluation dataset contains an invalid case");
            }
            if (evaluationCase.shouldAnswer() && evaluationCase.expectedFileNames().isEmpty()) {
                throw new IllegalStateException(
                        "Positive RAG evaluation case must declare expected files: " + evaluationCase.id());
            }
            if (!evaluationCase.shouldAnswer() && !evaluationCase.expectedFileNames().isEmpty()) {
                throw new IllegalStateException(
                        "Negative RAG evaluation case cannot declare expected files: " + evaluationCase.id());
            }
            Set<String> expectedFileNames = new HashSet<>();
            for (String fileName : evaluationCase.expectedFileNames()) {
                if (fileName == null || fileName.isBlank() || !expectedFileNames.add(fileName)) {
                    throw new IllegalStateException(
                            "RAG evaluation case contains an invalid expected file: " + evaluationCase.id());
                }
            }
        }
    }
}
