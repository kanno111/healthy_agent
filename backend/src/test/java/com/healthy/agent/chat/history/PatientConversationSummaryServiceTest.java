package com.healthy.agent.chat.history;

import com.healthy.agent.llm.ChatModelClient;
import com.healthy.agent.llm.ChatModelResult;
import com.healthy.agent.llm.TokenUsage;
import com.healthy.agent.trace.AgentTraceRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatientConversationSummaryServiceTest {
    private static final String CONVERSATION =
            "patient:8:11111111-1111-4111-8111-111111111111";
    private static final Instant NOW = Instant.parse("2026-10-12T02:00:00Z");

    private final PatientConversationHistoryService historyService =
            mock(PatientConversationHistoryService.class);
    private final ChatModelClient chatModelClient = mock(ChatModelClient.class);
    private final PatientConversationSummaryService service =
            new PatientConversationSummaryService(
                    historyService,
                    chatModelClient,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void incrementallyMergesCompleteOldTurnsAndReturnsPromptMemory() {
        when(chatModelClient.model()).thenReturn("deepseek-flash");
        when(historyService.memorySummary(CONVERSATION))
                .thenReturn(ConversationMemorySummary.empty());
        when(historyService.nextSummaryBatch(
                CONVERSATION, 0L, 18, 20)).thenReturn(new ConversationSummaryBatch(
                List.of(
                        new ConversationMemoryMessage(1L, ConversationRole.USER,
                                "下周想看神经内科"),
                        new ConversationMemoryMessage(2L, ConversationRole.ASSISTANT,
                                "已经列出医生")),
                2L));
        when(historyService.nextSummaryBatch(
                CONVERSATION, 2L, 18, 20))
                .thenReturn(ConversationSummaryBatch.empty(2L));
        when(chatModelClient.generate(anyString(), anyString(), eq(400)))
                .thenReturn(new ChatModelResult(
                        "用户想看神经内科；敏感值 Bearer secret-token 不保留。",
                        "deepseek-flash",
                        new TokenUsage(40, 10, 50)));
        when(historyService.saveMemorySummary(
                eq(CONVERSATION), eq(0L),
                org.mockito.ArgumentMatchers.any(ConversationMemorySummary.class)))
                .thenReturn(true);

        AgentTraceRecorder trace = AgentTraceRecorder.create();
        ConversationMemoryContext result = service.prepare(CONVERSATION, trace);
        trace.complete();

        assertThat(result.hasSummary()).isTrue();
        assertThat(result.summary()).contains("神经内科", "[REDACTED]")
                .doesNotContain("secret-token");
        assertThat(result.summarizedThroughSequence()).isEqualTo(2L);
        assertThat(result.recentMessageLimit()).isEqualTo(18);
        assertThat(result.usage().totalTokens()).isEqualTo(50);
        ArgumentCaptor<ConversationMemorySummary> saved =
                ArgumentCaptor.forClass(ConversationMemorySummary.class);
        verify(historyService).saveMemorySummary(eq(CONVERSATION), eq(0L), saved.capture());
        assertThat(saved.getValue().updatedAt()).isEqualTo(NOW);
        assertThat(trace.snapshot().steps()).singleElement().satisfies(step -> {
            assertThat(step.name()).isEqualTo("较早对话滚动摘要");
            assertThat(step.details()).containsEntry("summaryCalls", 1)
                    .containsEntry("totalTokens", 50);
        });
    }

    @Test
    void summaryFailureKeepsExistingSummaryAndDoesNotFailConversation() {
        ConversationMemorySummary existing = new ConversationMemorySummary(
                "已有摘要", 2L, NOW.minusSeconds(60));
        when(chatModelClient.model()).thenReturn("deepseek-flash");
        when(historyService.memorySummary(CONVERSATION)).thenReturn(existing);
        when(historyService.nextSummaryBatch(
                CONVERSATION, 2L, 18, 20)).thenReturn(new ConversationSummaryBatch(
                List.of(
                        new ConversationMemoryMessage(3L, ConversationRole.USER, "继续询问"),
                        new ConversationMemoryMessage(4L, ConversationRole.ASSISTANT, "继续回答")),
                4L));
        when(chatModelClient.generate(anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("timeout"));

        AgentTraceRecorder trace = AgentTraceRecorder.create();
        ConversationMemoryContext result = service.prepare(CONVERSATION, trace);
        trace.complete();

        assertThat(result.summary()).isEqualTo("已有摘要");
        assertThat(result.summarizedThroughSequence()).isEqualTo(2L);
        verify(historyService, never()).saveMemorySummary(
                anyString(), anyLong(),
                org.mockito.ArgumentMatchers.any(ConversationMemorySummary.class));
        assertThat(trace.snapshot().steps()).singleElement().satisfies(step -> {
            assertThat(step.status().name()).isEqualTo("FAILED");
            assertThat(step.summary()).contains("降级");
            assertThat(step.details().toString()).doesNotContain("timeout");
        });
    }

    @Test
    void noOverflowAvoidsAnExtraModelCall() {
        when(chatModelClient.model()).thenReturn("deepseek-flash");
        when(historyService.memorySummary(CONVERSATION))
                .thenReturn(ConversationMemorySummary.empty());
        when(historyService.nextSummaryBatch(
                CONVERSATION, 0L, 18, 20))
                .thenReturn(ConversationSummaryBatch.empty(0L));

        ConversationMemoryContext result = service.prepare(
                CONVERSATION, AgentTraceRecorder.create());

        assertThat(result.hasSummary()).isFalse();
        assertThat(result.recentMessageLimit()).isEqualTo(19);
        verify(chatModelClient, never()).generate(anyString(), anyString(), anyInt());
    }
}
