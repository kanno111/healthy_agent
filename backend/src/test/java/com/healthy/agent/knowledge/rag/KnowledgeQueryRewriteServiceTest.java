package com.healthy.agent.knowledge.rag;

import com.healthy.agent.llm.ChatModelClient;
import com.healthy.agent.llm.ChatModelResult;
import com.healthy.agent.llm.TokenUsage;
import com.healthy.agent.trace.AgentTraceRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeQueryRewriteServiceTest {
    private final ChatModelClient chatModelClient = mock(ChatModelClient.class);
    private final KnowledgeQueryRewriteService service =
            new KnowledgeQueryRewriteService(chatModelClient);

    @Test
    void rewritesFollowUpUsingBoundedConversationContextAtZeroTemperature() {
        when(chatModelClient.generate(anyString(), anyString(), eq(200), eq(0.0)))
                .thenReturn(new ChatModelResult(
                        "“医院住院病区周末探视时间和规定是什么？”\n",
                        "deepseek-flash",
                        new TokenUsage(80, 12, 92)));
        KnowledgeQueryRewriteContext context = new KnowledgeQueryRewriteContext(
                "那周末呢？",
                "用户此前询问住院探视规定。",
                List.of(
                        new UserMessage("住院可以探视吗？"),
                        new AssistantMessage("探视有时间限制。")));

        AgentTraceRecorder trace = AgentTraceRecorder.create();
        KnowledgeQueryRewriteResult result = service.rewrite(
                "住院探视周末规定", context, trace);
        trace.complete();

        assertThat(result.query()).isEqualTo("医院住院病区周末探视时间和规定是什么？");
        assertThat(result.changed()).isTrue();
        assertThat(result.fallback()).isFalse();
        assertThat(result.usage().totalTokens()).isEqualTo(92);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatModelClient).generate(anyString(), prompt.capture(), eq(200), eq(0.0));
        assertThat(prompt.getValue()).contains(
                "那周末呢", "住院探视周末规定", "用户此前询问住院探视规定",
                "住院可以探视吗", "探视有时间限制");
        assertThat(trace.snapshot().steps()).singleElement().satisfies(step -> {
            assertThat(step.name()).isEqualTo("RAG 查询改写");
            assertThat(step.status().name()).isEqualTo("SUCCESS");
            assertThat(step.details()).containsEntry("changed", true)
                    .containsEntry("totalTokens", 92);
            assertThat(step.toString()).doesNotContain("住院病区", "那周末呢");
        });
    }

    @Test
    void modelFailureFallsBackToOriginalQueryWithoutBlockingRag() {
        when(chatModelClient.model()).thenReturn("deepseek-flash");
        when(chatModelClient.generate(anyString(), anyString(), eq(200), eq(0.0)))
                .thenThrow(new IllegalStateException("timeout with sensitive text"));

        AgentTraceRecorder trace = AgentTraceRecorder.create();
        KnowledgeQueryRewriteResult result = service.rewrite(
                "门诊退费怎么办", KnowledgeQueryRewriteContext.empty(), trace);
        trace.complete();

        assertThat(result.query()).isEqualTo("门诊退费怎么办");
        assertThat(result.fallback()).isTrue();
        assertThat(result.usage()).isEqualTo(TokenUsage.empty());
        assertThat(trace.snapshot().steps()).singleElement().satisfies(step -> {
            assertThat(step.status().name()).isEqualTo("FAILED");
            assertThat(step.summary()).contains("回退原查询");
            assertThat(step.toString()).doesNotContain("sensitive text", "门诊退费怎么办");
        });
    }

    @Test
    void invalidModelOutputFallsBackButStillCountsConsumedTokens() {
        when(chatModelClient.generate(anyString(), anyString(), eq(200), eq(0.0)))
                .thenReturn(new ChatModelResult(
                        "x".repeat(1001),
                        "deepseek-flash",
                        new TokenUsage(50, 20, 70)));

        KnowledgeQueryRewriteResult result = service.rewrite(
                "报告怎么领取", KnowledgeQueryRewriteContext.empty(),
                AgentTraceRecorder.create());

        assertThat(result.query()).isEqualTo("报告怎么领取");
        assertThat(result.fallback()).isTrue();
        assertThat(result.usage().totalTokens()).isEqualTo(70);
    }
}
