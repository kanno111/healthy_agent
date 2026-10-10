package com.healthy.agent.knowledge.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRetrievalEvaluationDatasetLoaderTest {
    @Test
    void loadsVersionedDatasetWithPositiveAndNegativeCases() {
        KnowledgeRetrievalEvaluationDataset dataset =
                new KnowledgeRetrievalEvaluationDatasetLoader(new ObjectMapper()).get();

        assertThat(dataset.version()).isEqualTo("v3-2026-10-09");
        assertThat(dataset.cases()).hasSize(104);
        assertThat(dataset.cases()).filteredOn(KnowledgeRetrievalEvaluationCase::shouldAnswer)
                .hasSize(96);
        assertThat(dataset.cases()).filteredOn(item -> !item.shouldAnswer())
                .hasSize(8)
                .allSatisfy(item -> assertThat(item.expectedFileNames()).isEmpty());
        assertThat(dataset.cases()).filteredOn(item -> item.shouldAnswer()
                        && item.expectedFileNames().size() == 1)
                .hasSize(72);
        assertThat(dataset.cases()).filteredOn(item -> item.shouldAnswer()
                        && item.expectedFileNames().size() > 1)
                .hasSize(24)
                .allSatisfy(item -> {
                    assertThat(item.id()).isBetween("rag-081", "rag-104");
                    assertThat(item.expectedFileNames()).hasSizeBetween(2, 3);
                    assertThat(item.expectedFileNames()).doesNotHaveDuplicates();
                });
    }
}
