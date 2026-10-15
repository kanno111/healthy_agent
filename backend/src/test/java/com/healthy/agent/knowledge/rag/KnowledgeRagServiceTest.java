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
import com.healthy.agent.trace.AgentTraceRecorder;
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
    private KnowledgeQueryRewriteService queryRewriteService;
    private ChatModelClient chatModelClient;
    private KnowledgeRagService service;

    @BeforeEach
    void setUp() {
        searchService = mock(KnowledgeSearchService.class);
        queryRewriteService = mock(KnowledgeQueryRewriteService.class);
        chatModelClient = mock(ChatModelClient.class);
        service = new KnowledgeRagService(
                searchService,
                queryRewriteService,
                chatModelClient,
                new RagProperties(5, 8, 0.65, 6000)
        );
    }

    @Test
    void retrievesContextGeneratesAnswerAndReturnsCitationsAndUsage() {
        KnowledgeSearchHit relevant = hit(1, "预约制度.md", "就诊日前可以取消预约。", 0.91);
        KnowledgeSearchHit belowThreshold = hit(2, "探视制度.md", "住院探视时间。", 0.50);
        when(queryRewriteService.rewrite(
                org.mockito.ArgumentMatchers.eq("如何取消预约？"),
                org.mockito.ArgumentMatchers.any(KnowledgeQueryRewriteContext.class),
                org.mockito.ArgumentMatchers.any(AgentTraceRecorder.class)))
                .thenReturn(new KnowledgeQueryRewriteResult(
                        "门诊预约取消规定", true, false, "deepseek-flash",
                        new TokenUsage(30, 10, 40)));
        when(searchService.search(
                org.mockito.ArgumentMatchers.eq(
                        new KnowledgeSearchRequest("门诊预约取消规定", 5)),
                org.mockito.ArgumentMatchers.any(AgentTraceRecorder.class)))
                .thenReturn(new KnowledgeSearchResponse(
                        "如何取消预约？", "BAAI/bge-m3", 5,
                        List.of(relevant, belowThreshold)));
        when(chatModelClient.generate(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ChatModelResult(
                        "可在就诊日前取消。[资料1]", "deepseek-flash",
                        new TokenUsage(140, 20, 160)));

        AgentTraceRecorder trace = AgentTraceRecorder.create();
        KnowledgeRagResponse response = service.answer(
                new KnowledgeRagRequest("  如何取消预约？  ", null), trace);
        trace.complete();

        assertThat(response.answer()).isEqualTo("可在就诊日前取消。[资料1]");
        assertThat(response.model()).isEqualTo("deepseek-flash");
        assertThat(response.embeddingModel()).isEqualTo("BAAI/bge-m3");
        assertThat(response.usage().totalTokens()).isEqualTo(200);
        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().getFirst().fileName()).isEqualTo("预约制度.md");
        assertThat(trace.snapshot().steps())
                .extracting(step -> step.name())
                .contains("RAG 知识库问答", "RAG 答案生成");
        assertThat(trace.snapshot().steps().toString())
                .contains("doc-1-1", "预约制度.md", "totalTokens=160")
                .doesNotContain("如何取消预约", "就诊日前可以取消预约");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModelClient).generate(org.mockito.ArgumentMatchers.anyString(), promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("[资料1]", "预约制度.md", "就诊日前可以取消预约。", "如何取消预约？")
                .doesNotContain("住院探视时间");
    }

    @Test
    void skipsLlmWhenNoChunkMeetsThreshold() {
        when(queryRewriteService.rewrite(
                org.mockito.ArgumentMatchers.eq("未知问题"),
                org.mockito.ArgumentMatchers.any(KnowledgeQueryRewriteContext.class),
                org.mockito.ArgumentMatchers.any(AgentTraceRecorder.class)))
                .thenReturn(new KnowledgeQueryRewriteResult(
                        "未知问题", false, false, "deepseek-flash",
                        new TokenUsage(20, 5, 25)));
        when(searchService.search(
                org.mockito.ArgumentMatchers.eq(
                        new KnowledgeSearchRequest("未知问题", 3)),
                org.mockito.ArgumentMatchers.any(AgentTraceRecorder.class)))
                .thenReturn(new KnowledgeSearchResponse(
                        "未知问题", "BAAI/bge-m3", 3,
                        List.of(hit(1, "无关文档.md", "无关内容", 0.40))));
        when(chatModelClient.model()).thenReturn("deepseek-flash");

        KnowledgeRagResponse response = service.answer(new KnowledgeRagRequest("未知问题", 3));

        assertThat(response.answer()).isEqualTo(KnowledgeRagService.NO_ANSWER);
        assertThat(response.citations()).isEmpty();
        assertThat(response.usage()).isEqualTo(new TokenUsage(20, 5, 25));
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
        verifyNoInteractions(searchService, queryRewriteService, chatModelClient);
    }

    private KnowledgeSearchHit hit(int rank, String fileName, String content, double score) {
        return new KnowledgeSearchHit(
                rank, "doc-1-" + rank, "doc-1", fileName, "text/markdown",
                rank - 1, content, score
        );
    }
}
