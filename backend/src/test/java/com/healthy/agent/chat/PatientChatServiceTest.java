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
import com.healthy.agent.chat.routing.PatientIntentRouter;
import com.healthy.agent.chat.routing.PatientIntentRoute;
import com.healthy.agent.chat.routing.PatientIntentRoutingDecision;
import com.healthy.agent.chat.routing.PatientIntentToolSelector;
import com.healthy.agent.chat.history.PatientConversationHistoryService;
import com.healthy.agent.chat.history.PatientConversationSummaryService;
import com.healthy.agent.chat.history.ConversationMemoryContext;
import com.healthy.agent.llm.TokenUsage;
import com.healthy.agent.memory.profile.PatientProfileExtractionResult;
import com.healthy.agent.memory.profile.PatientProfileExtractionService;
import com.healthy.agent.memory.profile.PatientProfilePatch;
import com.healthy.agent.memory.profile.PatientProfileService;
import com.healthy.agent.memory.profile.ProfileFieldPatch;
import com.healthy.agent.memory.profile.ProfilePatchOperation;
import com.healthy.agent.state.AgentStateService;
import com.healthy.agent.state.Candidate;
import com.healthy.agent.state.CandidateResultSet;
import com.healthy.agent.state.CandidateType;
import com.healthy.agent.tool.PatientSpringAiTools;
import com.healthy.agent.trace.AgentTraceRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private final ChatClient.StreamResponseSpec streamCall = mock(ChatClient.StreamResponseSpec.class);
    private final PatientSpringAiTools tools = mock(PatientSpringAiTools.class);
    private final PatientActionService actionService = mock(PatientActionService.class);
    private final AgentStateService stateService = new AgentStateService();
    private final PatientIntentRouter intentRouter = mock(PatientIntentRouter.class);
    private final PatientIntentToolSelector intentToolSelector = new PatientIntentToolSelector();
    private final LlmProperties properties = new LlmProperties(
            "https://api.deepseek.com", "test-key", "deepseek-flash",
            2048, 0.1, Duration.ofSeconds(5), Duration.ofSeconds(30));
    private final PatientConversationHistoryService historyService =
            mock(PatientConversationHistoryService.class);
    private final PatientConversationSummaryService summaryService =
            mock(PatientConversationSummaryService.class);
    private final PatientProfileService profileService = mock(PatientProfileService.class);
    private final PatientProfileExtractionService profileExtractionService =
            mock(PatientProfileExtractionService.class);
    private final PatientChatService service = new PatientChatService(
            chatClient, tools, actionService, stateService,
            intentRouter, intentToolSelector, properties, historyService, summaryService,
            profileService, profileExtractionService);

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUpChatClient() {
        when(intentRouter.route(anyString(), anyString(), anyString(),
                any(AgentTraceRecorder.class))).thenReturn(
                PatientIntentRoutingDecision.fallback("test fallback", null));
        when(tools.callbacks()).thenReturn(List.of());
        when(chatClient.prompt()).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.messages(anyList())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.tools(any(Object[].class))).thenReturn(request);
        when(request.toolContext(anyMap())).thenReturn(request);
        when(request.advisors(any(Consumer.class))).thenReturn(request);
        when(request.options(any(ChatOptions.Builder.class))).thenReturn(request);
        when(request.call()).thenReturn(call);
        when(request.stream()).thenReturn(streamCall);
        when(historyService.requireOwned(8L, CONVERSATION_ID)).thenReturn(SCOPED);
        when(historyService.recentModelMessages(anyString(), anyInt()))
                .thenReturn(List.of());
        when(summaryService.prepare(anyString(), any(AgentTraceRecorder.class)))
                .thenReturn(ConversationMemoryContext.empty());
        when(profileService.promptSummary(8L)).thenReturn("无已保存偏好");
        when(profileExtractionService.extract(
                eq(8L), anyString(), any(AgentTraceRecorder.class)))
                .thenReturn(PatientProfileExtractionResult.empty());
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
        assertThat(result.turnId()).isNotBlank();
        assertThat(result.trace().turnId()).isEqualTo(result.turnId());
        assertThat(result.trace().steps())
                .extracting(step -> step.name())
                .contains("Patient Agent 回答");
        assertThat(result.trace().toString()).doesNotContain("very-secret-jwt");
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
        verify(historyService).recentModelMessages(SCOPED, 19);
        verify(historyService).appendExchange(
                8L, CONVERSATION_ID, "查询我的预约", result);
    }

    @Test
    void addsProfileExtractionUsageAndAppliesPatchOnlyAfterNewExchangeIsStored() {
        when(actionService.activeAction(SCOPED)).thenReturn(Optional.empty());
        when(call.chatResponse()).thenReturn(response(
                "好的，以后优先提供上午时段。", "deepseek-flash", 100, 20));
        PatientProfilePatch patch = new PatientProfilePatch(
                new ProfileFieldPatch(ProfilePatchOperation.SET, "MORNING"),
                ProfileFieldPatch.keep());
        when(profileExtractionService.extract(
                eq(8L), anyString(), any(AgentTraceRecorder.class)))
                .thenReturn(new PatientProfileExtractionResult(
                        patch, new TokenUsage(30, 10, 40)));
        when(historyService.appendExchange(
                eq(8L), eq(CONVERSATION_ID), anyString(), any(PatientChatResponse.class)))
                .thenReturn(true);

        PatientChatResponse result = service.answer(
                "请记住我以后更喜欢上午", "Bearer token", 8L, CONVERSATION_ID);

        assertThat(result.usage().totalTokens()).isEqualTo(160);
        verify(profileService).applyPatch(8L, patch);
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
    void chatRouteRegistersNoToolsAndCountsRouterTokens() {
        when(actionService.activeAction(SCOPED)).thenReturn(Optional.empty());
        when(intentRouter.route(anyString(), anyString(), anyString(),
                any(AgentTraceRecorder.class))).thenReturn(
                PatientIntentRoutingDecision.routed(
                        Set.of(PatientIntentRoute.CHAT), "普通聊天",
                        new TokenUsage(8, 4, 12)));
        when(tools.callbacks()).thenReturn(List.of(callback("list_departments")));
        when(call.chatResponse()).thenReturn(response(
                "冬季注意保暖和手卫生。", "deepseek-flash", 100, 20));

        PatientChatResponse result = service.answer(
                "为什么冬天容易感冒？", "Bearer token", 8L, CONVERSATION_ID);

        verify(request, never()).tools(any(Object[].class));
        assertThat(result.usage().totalTokens()).isEqualTo(132);
    }

    @Test
    void prependsRollingSummaryWithoutExpandingTwentyMessageWindow() {
        when(actionService.activeAction(SCOPED)).thenReturn(Optional.empty());
        ConversationMemoryContext memory = new ConversationMemoryContext(
                "用户此前想看神经内科，下周继续筛选医生。",
                2L,
                new TokenUsage(30, 10, 40));
        when(summaryService.prepare(eq(SCOPED), any(AgentTraceRecorder.class)))
                .thenReturn(memory);
        when(intentRouter.route(anyString(), eq(SCOPED), eq(memory.summary()),
                any(AgentTraceRecorder.class))).thenReturn(
                PatientIntentRoutingDecision.routed(
                        Set.of(PatientIntentRoute.CHAT), "测试摘要上下文",
                        new TokenUsage(8, 4, 12)));
        when(historyService.recentModelMessages(SCOPED, 18))
                .thenReturn(List.of(new AssistantMessage("最近一条原文")));
        when(call.chatResponse()).thenReturn(response(
                "继续为你查询。", "deepseek-flash", 100, 20));

        PatientChatResponse result = service.answer(
                "那下周呢？", "Bearer token", 8L, CONVERSATION_ID);

        assertThat(result.usage().totalTokens()).isEqualTo(172);
        verify(historyService).recentModelMessages(SCOPED, 18);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> messages = ArgumentCaptor.forClass(List.class);
        verify(request).messages(messages.capture());
        @SuppressWarnings("unchecked")
        List<Message> promptMessages = (List<Message>) messages.getValue();
        assertThat(promptMessages).hasSize(2);
        assertThat(promptMessages.getFirst().getText())
                .contains("较早对话摘要", "神经内科", "不得据此恢复或猜测任何内部业务 ID");
        assertThat(promptMessages.getLast().getText()).isEqualTo("最近一条原文");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> toolContext = ArgumentCaptor.forClass(Map.class);
        verify(request).toolContext(toolContext.capture());
        PatientToolExecutionContext executionContext = (PatientToolExecutionContext)
                toolContext.getValue().get(PatientToolExecutionContext.TOOL_CONTEXT_KEY);
        assertThat(executionContext.conversationSummary()).isEqualTo(memory.summary());
        assertThat(executionContext.recentConversationMessages())
                .singleElement().extracting(Message::getText).isEqualTo("最近一条原文");
    }

    @Test
    void queryRouteRegistersOnlyQueryGroupCallbacks() {
        when(actionService.activeAction(SCOPED)).thenReturn(Optional.empty());
        when(intentRouter.route(anyString(), anyString(), anyString(),
                any(AgentTraceRecorder.class))).thenReturn(
                PatientIntentRoutingDecision.routed(
                        Set.of(PatientIntentRoute.QUERY), "业务查询", TokenUsage.empty()));
        when(tools.callbacks()).thenReturn(List.of(
                callback("list_departments"),
                callback(PatientSpringAiTools.RAG_TOOL),
                callback(PatientActionService.PREPARE_CREATE_APPOINTMENT)));
        when(call.chatResponse()).thenReturn(response(
                "已查询科室。", "deepseek-flash", 100, 20));

        service.answer("有哪些科室？", "Bearer token", 8L, CONVERSATION_ID);

        ArgumentCaptor<Object[]> callbacks = ArgumentCaptor.forClass(Object[].class);
        verify(request).tools(callbacks.capture());
        assertThat(callbacks.getValue()).hasSize(1);
        ToolCallback selected = (ToolCallback) callbacks.getValue()[0];
        assertThat(selected.getToolDefinition().name()).isEqualTo("list_departments");
    }

    @Test
    void streamsChatDeltasAndCompletesWithAggregatedMetadata() {
        when(actionService.activeAction(SCOPED)).thenReturn(Optional.empty());
        when(intentRouter.route(anyString(), anyString(), anyString(),
                any(AgentTraceRecorder.class))).thenReturn(
                PatientIntentRoutingDecision.routed(
                        Set.of(PatientIntentRoute.CHAT), "普通聊天",
                        new TokenUsage(8, 4, 12)));
        when(streamCall.chatResponse()).thenReturn(Flux.just(
                response("你", "deepseek-flash", 0, 0),
                response("好", "deepseek-flash", 100, 20)));

        List<PatientChatStreamEvent> events = service.stream(
                        "你好", "Bearer token", 8L, CONVERSATION_ID)
                .collectList().block();

        assertThat(events).isNotNull().hasSize(4);
        assertThat(events.subList(0, 2))
                .extracting(PatientChatStreamEvent::type, PatientChatStreamEvent::content)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("delta", "你"),
                        org.assertj.core.groups.Tuple.tuple("delta", "好"));
        assertThat(events.get(2).type()).isEqualTo("trace");
        assertThat(events.get(2).trace().steps())
                .extracting(step -> step.type().name())
                .contains("LLM");
        PatientChatResponse completed = events.getLast().response();
        assertThat(events.getLast().type()).isEqualTo("complete");
        assertThat(completed.turnId()).isEqualTo(events.get(2).turnId());
        assertThat(completed.trace()).isNull();
        assertThat(completed.answer()).isEqualTo("你好");
        assertThat(completed.usage().totalTokens()).isEqualTo(132);
        verify(request).stream();
        verify(historyService).appendExchange(
                org.mockito.ArgumentMatchers.eq(8L),
                org.mockito.ArgumentMatchers.eq(CONVERSATION_ID),
                org.mockito.ArgumentMatchers.eq("你好"),
                any(PatientChatResponse.class));
    }

    @Test
    void toolChainPublishesOnlyFinalConfirmationInsteadOfIntermediateAppointmentList() {
        when(actionService.activeAction(SCOPED)).thenReturn(Optional.empty());
        when(intentRouter.route(anyString(), anyString(), anyString(),
                any(AgentTraceRecorder.class))).thenReturn(
                PatientIntentRoutingDecision.routed(
                        Set.of(PatientIntentRoute.WRITE), "取消预约", TokenUsage.empty()));
        PendingActionView pending = pending();
        String confirmation = "取消预约属于写操作，请确认是否取消。";
        when(actionService.confirmationMessage(pending)).thenReturn(confirmation);

        AtomicReference<PatientToolExecutionContext> executionContext = new AtomicReference<>();
        when(request.toolContext(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> toolContext = invocation.getArgument(0);
            executionContext.set((PatientToolExecutionContext) toolContext.get(
                    PatientToolExecutionContext.TOOL_CONTEXT_KEY));
            return request;
        });
        when(streamCall.chatResponse()).thenAnswer(ignored -> Flux.concat(
                Mono.fromSupplier(() -> {
                    executionContext.get().directAnswer("您当前有以下预约：第一条、第二条");
                    return response("正在查询预约", "deepseek-flash", 0, 0);
                }),
                Mono.fromSupplier(() -> {
                    executionContext.get().pendingAction(pending);
                    executionContext.get().directAnswer(confirmation);
                    return response("已准备取消", "deepseek-flash", 100, 20);
                })));

        List<PatientChatStreamEvent> events = service.stream(
                        "取消刚才确定的预约", "Bearer token", 8L, CONVERSATION_ID)
                .collectList().block();

        assertThat(events).isNotNull().hasSize(3);
        assertThat(events.getFirst().type()).isEqualTo("delta");
        assertThat(events.getFirst().content()).isEqualTo(confirmation);
        assertThat(events).noneMatch(event -> event.content() != null
                && event.content().contains("您当前有以下预约"));
        assertThat(events.get(1).type()).isEqualTo("trace");
        assertThat(events.getLast().type()).isEqualTo("complete");
        assertThat(events.getLast().response().answer()).isEqualTo(confirmation);
        assertThat(events.getLast().response().pendingAction()).isEqualTo(pending);
    }

    @Test
    void streamingFailureReturnsSafeErrorEvent() {
        when(actionService.activeAction(SCOPED)).thenReturn(Optional.empty());
        when(streamCall.chatResponse()).thenReturn(
                Flux.error(new IllegalStateException("provider disconnected")));

        List<PatientChatStreamEvent> events = service.stream(
                        "你好", "Bearer token", 8L, CONVERSATION_ID)
                .collectList().block();

        assertThat(events).hasSize(2);
        assertThat(events.getFirst().type()).isEqualTo("trace");
        assertThat(events.getFirst().trace().turnId()).isNotBlank();
        assertThat(events.getLast()).satisfies(event -> {
            assertThat(event.type()).isEqualTo("error");
            assertThat(event.code()).isEqualTo(50314);
            assertThat(event.message()).isEqualTo("大模型问答服务暂时不可用");
        });
    }

    @Test
    void unrelatedAnswerDoesNotDiscardPreviousBusinessResultSet() {
        Candidate doctor = new Candidate(
                102L, CandidateType.DOCTOR, "李医生 神经内科",
                102L, "李医生", 1L, "神经内科",
                null, null, null, null, null);
        CandidateResultSet doctors = stateService.addResultSet(
                SCOPED, "下周有号医生", CandidateType.DOCTOR, List.of(doctor));
        when(actionService.activeAction(SCOPED)).thenReturn(Optional.empty());
        when(call.chatResponse()).thenReturn(response(
                "冬季注意保暖和手卫生。", "deepseek-flash", 80, 15));

        service.answer("为什么冬天容易感冒？", "Bearer token", 8L, CONVERSATION_ID);

        assertThat(stateService.getCurrentResultSet(SCOPED))
                .get().extracting(CandidateResultSet::resultSetId)
                .isEqualTo(doctors.resultSetId());
        assertThat(stateService.getOrCreate(SCOPED).recentResultSets())
                .containsExactly(doctors);
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
        stateService.prepareAction(SCOPED, pending);
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
                .contains("pendingAction=none")
                .contains("lastActionResult={type=CREATE_APPOINTMENT, status=SUCCEEDED")
                .contains("REJECTED、EXPIRED、FAILED 和 SUCCEEDED 都是不可恢复的终态")
                .contains("不得根据 ChatMemory 声称终态旧卡仍有效")
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

    private ToolCallback callback(String name) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name).description(name).inputSchema("{\"type\":\"object\"}").build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public ToolMetadata getToolMetadata() { return ToolMetadata.builder().build(); }
            @Override public String call(String toolInput) { return ""; }
        };
    }
}
