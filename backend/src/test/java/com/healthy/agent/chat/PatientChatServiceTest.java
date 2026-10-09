package com.healthy.agent.chat;

import com.healthy.agent.action.ActionPreviewField;
import com.healthy.agent.action.PatientActionPreview;
import com.healthy.agent.action.PatientActionResponse;
import com.healthy.agent.action.PatientActionService;
import com.healthy.agent.action.PatientActionStatus;
import com.healthy.agent.action.PatientActionType;
import com.healthy.agent.action.PendingActionView;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.config.LlmProperties;
import com.healthy.agent.state.AgentStateService;
import com.healthy.agent.state.AgentTaskType;
import com.healthy.agent.tool.PatientSpringAiTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatientChatServiceTest {
    private static final String CONVERSATION_ID = "11111111-1111-4111-8111-111111111111";
    private static final String SCOPED = "patient:8:" + CONVERSATION_ID;

    private final ChatClient chatClient = mock(ChatClient.class);
    private final ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
    private final ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
    private final PatientSpringAiTools tools = mock(PatientSpringAiTools.class);
    private final PatientActionService actionService = mock(PatientActionService.class);
    private final AgentStateService stateService = new AgentStateService();
    private final LlmProperties properties = new LlmProperties(
            "https://api.deepseek.com", "test-key", "deepseek-flash",
            2048, 0.1, Duration.ofSeconds(5), Duration.ofSeconds(30));
    private final PatientChatService service = new PatientChatService(
            chatClient, tools, actionService, stateService, properties);

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUpChatClient() {
        when(tools.callbacks()).thenReturn(List.of());
        when(chatClient.prompt()).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.tools(any(Object[].class))).thenReturn(request);
        when(request.toolContext(anyMap())).thenReturn(request);
        when(request.advisors(any(Consumer.class))).thenReturn(request);
        when(request.options(any(ChatOptions.Builder.class))).thenReturn(request);
        when(request.call()).thenReturn(call);
    }

    @Test
    void delegatesConversationToSpringAiWithoutPuttingJwtInPrompt() {
        when(actionService.activeAction(SCOPED)).thenReturn(Optional.empty());
        when(call.chatResponse()).thenReturn(response(
                "你目前没有预约记录。", "deepseek-flash", 100, 20));

        PatientChatResponse result = service.answer(
                "查询我的预约", "Bearer very-secret-jwt", 8L, CONVERSATION_ID);

        assertThat(result.answer()).isEqualTo("你目前没有预约记录。");
        assertThat(result.usage().totalTokens()).isEqualTo(120);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(request).system(system.capture());
        assertThat(system.getValue()).contains("recentResultSets").doesNotContain("very-secret-jwt");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(request).toolContext(context.capture());
        PatientToolExecutionContext state = (PatientToolExecutionContext) context.getValue()
                .get(PatientToolExecutionContext.TOOL_CONTEXT_KEY);
        assertThat(state.authorization()).isEqualTo("Bearer very-secret-jwt");
        assertThat(state.conversationId()).isEqualTo(SCOPED);
    }

    @Test
    void rejectsMissingOrInvalidConversationIdBeforeCallingModel() {
        assertThatThrownBy(() -> service.answer("查询预约", "Bearer token", 8L, null))
                .isInstanceOf(AgentException.class)
                .extracting("errorCode.code").isEqualTo(40018);
        assertThatThrownBy(() -> service.answer("查询预约", "Bearer token", 8L, "not-a-uuid"))
                .isInstanceOf(AgentException.class)
                .extracting("errorCode.code").isEqualTo(40018);
    }

    @Test
    void leavesToolSelectionAutomaticForCancellationRequests() {
        when(actionService.activeAction(SCOPED)).thenReturn(Optional.empty());
        when(call.chatResponse()).thenReturn(response(
                "工具将直接返回结果。", "deepseek-flash", 100, 20));

        service.answer("取消预约号82", "Bearer token", 8L, CONVERSATION_ID);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<ChatOptions.Builder> options = ArgumentCaptor.forClass(ChatOptions.Builder.class);
        verify(request).options(options.capture());
        OpenAiChatOptions built = (OpenAiChatOptions) options.getValue().build();
        assertThat(built.getToolChoice()).isNull();
    }

    @Test
    void textConfirmationReturnsOriginalCardWithoutCallingModelOrExecuting() {
        PendingActionView pending = pending();
        when(actionService.activeAction(SCOPED)).thenReturn(Optional.of(pending));

        PatientChatResponse response = service.answer(
                "确认", "Bearer token", 8L, CONVERSATION_ID);

        assertThat(response.pendingAction()).isEqualTo(pending);
        assertThat(response.answer()).contains("点击原确认卡片");
        verify(chatClient, never()).prompt();
        verify(actionService, never()).confirm(anyString(), anyString(), any(Long.class), anyString());
    }

    @Test
    void abandonTextRejectsRealPendingActionAndReturnsUiUpdate() {
        PendingActionView pending = pending();
        PatientActionResponse rejected = new PatientActionResponse(
                pending.actionId(), pending.type(), PatientActionStatus.REJECTED,
                "已放弃本次操作", pending.preview());
        when(actionService.activeAction(SCOPED)).thenReturn(Optional.of(pending));
        when(actionService.reject(pending.actionId(), SCOPED, 8L)).thenReturn(rejected);

        PatientChatResponse response = service.answer(
                "算了", "Bearer token", 8L, CONVERSATION_ID);

        assertThat(response.actionUpdate()).isEqualTo(rejected);
        assertThat(response.pendingAction()).isNull();
        verify(actionService).reject(pending.actionId(), SCOPED, 8L);
        verify(chatClient, never()).prompt();
    }

    @Test
    void successfulButtonActionBecomesAuthoritativeStateForLaterTurns() {
        PendingActionView pending = new PendingActionView(
                "created-action", PatientActionType.CREATE_APPOINTMENT,
                PatientActionStatus.PENDING,
                new PatientActionPreview(
                        "确认创建预约", "请核对。", "确认预约", "暂不预约",
                        List.of(
                                new ActionPreviewField("doctorName", "医生", "何雨桐"),
                                new ActionPreviewField("scheduleDate", "日期", "2026-10-12"),
                                new ActionPreviewField("sessionName", "时段", "下午门诊"))),
                Instant.parse("2026-10-08T08:00:00Z"));
        stateService.prepareAction(SCOPED, AgentTaskType.CREATE_APPOINTMENT, pending);
        stateService.completeAction(SCOPED, pending.actionId(), new PatientActionResponse(
                pending.actionId(), pending.type(), PatientActionStatus.SUCCEEDED,
                "预约已成功创建", pending.preview()));
        when(actionService.activeAction(SCOPED)).thenReturn(Optional.empty());
        when(call.chatResponse()).thenReturn(response(
                "刚才的预约已创建，如不需要应发起取消。", "deepseek-flash", 100, 20));

        PatientChatResponse result = service.answer(
                "算了不想挂了", "Bearer token", 8L, CONVERSATION_ID);

        assertThat(result.answer()).contains("已创建");
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(request).system(system.capture());
        assertThat(system.getValue())
                .contains("lastActionResult={type=CREATE_APPOINTMENT, status=SUCCEEDED")
                .contains("医生=何雨桐", "日期=2026-10-12", "时段=下午门诊")
                .doesNotContain("created-action");
    }

    private PendingActionView pending() {
        return new PendingActionView(
                "action-2", PatientActionType.CANCEL_APPOINTMENT,
                PatientActionStatus.PENDING,
                new PatientActionPreview(
                        "确认取消预约", "请核对预约信息。", "确认取消", "暂不取消",
                        List.of(new ActionPreviewField("appointmentNo", "预约号", "A201"))),
                Instant.parse("2026-10-08T08:00:00Z"));
    }

    private ChatResponse response(String text, String model, int promptTokens, int completionTokens) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder()
                        .model(model)
                        .usage(new DefaultUsage(promptTokens, completionTokens,
                                promptTokens + completionTokens))
                        .build());
    }
}
