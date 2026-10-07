package com.healthy.agent.knowledge.rag;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.config.RagProperties;
import com.healthy.agent.knowledge.search.KnowledgeSearchHit;
import com.healthy.agent.knowledge.search.KnowledgeSearchRequest;
import com.healthy.agent.knowledge.search.KnowledgeSearchResponse;
import com.healthy.agent.knowledge.search.KnowledgeSearchService;
import com.healthy.agent.llm.ChatModelClient;
import com.healthy.agent.llm.ChatModelResult;
import com.healthy.agent.llm.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeRagServiceTest {
    private KnowledgeSearchService searchService;
    private ChatModelClient chatModelClient;
    private KnowledgeRagService service;

    @BeforeEach
    void setUp() {
        searchService = mock(KnowledgeSearchService.class);
        chatModelClient = mock(ChatModelClient.class);
        service = new KnowledgeRagService(
                searchService,
                chatModelClient,
                new RagProperties(5, 8, 0.65, 6000)
        );
    }

    @Test
    void retrievesContextGeneratesAnswerAndReturnsCitationsAndUsage() {
        KnowledgeSearchHit relevant = hit(1, "预约制度.md", "就诊日前可以取消预约。", 0.91);
        KnowledgeSearchHit belowThreshold = hit(2, "探视制度.md", "住院探视时间。", 0.50);
        when(searchService.search(new KnowledgeSearchRequest("如何取消预约？", 5)))
                .thenReturn(new KnowledgeSearchResponse(
                        "如何取消预约？", "BAAI/bge-m3", 5,
                        List.of(relevant, belowThreshold)));
        when(chatModelClient.generate(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ChatModelResult(
                        "可在就诊日前取消。[资料1]", "deepseek-flash",
                        new TokenUsage(140, 20, 160)));

        KnowledgeRagResponse response = service.answer(
                new KnowledgeRagRequest("  如何取消预约？  ", null));

        assertThat(response.answer()).isEqualTo("可在就诊日前取消。[资料1]");
        assertThat(response.model()).isEqualTo("deepseek-flash");
        assertThat(response.embeddingModel()).isEqualTo("BAAI/bge-m3");
        assertThat(response.usage().totalTokens()).isEqualTo(160);
        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().getFirst().fileName()).isEqualTo("预约制度.md");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModelClient).generate(org.mockito.ArgumentMatchers.anyString(), promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("[资料1]", "预约制度.md", "就诊日前可以取消预约。", "如何取消预约？")
                .doesNotContain("住院探视时间");
    }

    @Test
    void skipsLlmWhenNoChunkMeetsThreshold() {
        when(searchService.search(new KnowledgeSearchRequest("未知问题", 3)))
                .thenReturn(new KnowledgeSearchResponse(
                        "未知问题", "BAAI/bge-m3", 3,
                        List.of(hit(1, "无关文档.md", "无关内容", 0.40))));
        when(chatModelClient.model()).thenReturn("deepseek-flash");

        KnowledgeRagResponse response = service.answer(new KnowledgeRagRequest("未知问题", 3));

        assertThat(response.answer()).isEqualTo(KnowledgeRagService.NO_ANSWER);
        assertThat(response.citations()).isEmpty();
        assertThat(response.usage()).isEqualTo(TokenUsage.empty());
        verify(chatModelClient, never()).generate(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rejectsInvalidRequestBeforeRetrieval() {
        assertThatThrownBy(() -> service.answer(new KnowledgeRagRequest(" ", 5)))
                .isInstanceOfSatisfying(AgentException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(AgentErrorCode.INVALID_RAG_QUESTION));
        assertThatThrownBy(() -> service.answer(new KnowledgeRagRequest("预约", 9)))
                .isInstanceOfSatisfying(AgentException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(AgentErrorCode.INVALID_RAG_LIMIT));
        verifyNoInteractions(searchService, chatModelClient);
    }

    private KnowledgeSearchHit hit(int rank, String fileName, String content, double score) {
        return new KnowledgeSearchHit(
                rank, "doc-1-" + rank, "doc-1", fileName, "text/markdown",
                rank - 1, content, score
        );
    }
}
