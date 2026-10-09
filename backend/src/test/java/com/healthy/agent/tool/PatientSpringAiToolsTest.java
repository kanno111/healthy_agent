package com.healthy.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthy.agent.action.ActionPreparationResult;
import com.healthy.agent.action.ActionPreviewField;
import com.healthy.agent.action.PatientActionPreview;
import com.healthy.agent.action.PatientActionService;
import com.healthy.agent.action.PatientActionStatus;
import com.healthy.agent.action.PatientActionType;
import com.healthy.agent.action.PendingActionView;
import com.healthy.agent.chat.PatientToolExecutionContext;
import com.healthy.agent.knowledge.rag.KnowledgeRagRequest;
import com.healthy.agent.knowledge.rag.KnowledgeRagResponse;
import com.healthy.agent.knowledge.rag.KnowledgeRagService;
import com.healthy.agent.llm.TokenUsage;
import com.healthy.agent.state.AgentStateService;
import com.healthy.agent.state.Candidate;
import com.healthy.agent.state.CandidateType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatientSpringAiToolsTest {
    private static final String CONVERSATION =
            "patient:8:11111111-1111-4111-8111-111111111111";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PatientReadToolRegistry registry = mock(PatientReadToolRegistry.class);
    private final PatientActionService actionService = mock(PatientActionService.class);
    private final KnowledgeRagService ragService = mock(KnowledgeRagService.class);
    private final AgentStateService stateService = new AgentStateService();

    @Test
    void everyBusinessReadResultCreatesAResultSetWithoutExposingJwt() {
        when(registry.definitions()).thenReturn(List.of(definition("list_my_appointments")));
        var appointments = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("id", 201L).put("doctorId", 2L).put("doctorName", "陈书宁")
                .put("departmentId", 2L).put("departmentName", "消化内科")
                .put("scheduleDate", "2026-10-12").put("sessionName", "上午门诊")
                .put("startTime", "08:00:00").put("endTime", "12:00:00")
                .put("status", "BOOKED"));
        when(registry.execute("list_my_appointments", "{}", "Bearer secret-token", 8L))
                .thenReturn(ToolExecutionResult.success(appointments));
        PatientSpringAiTools tools = tools();
        PatientToolExecutionContext context = contextState();

        String output = find(tools, "list_my_appointments").call("{}", context(context));

        assertThat(output).contains("\"ok\":true").contains("resultSet")
                .doesNotContain("secret-token");
        assertThat(stateService.getCurrentResultSet(CONVERSATION)).isPresent()
                .get().extracting(result -> result.type()).isEqualTo(CandidateType.APPOINTMENT);
        assertThat(context.directAnswer()).contains("1. [有效预约]");
    }

    @Test
    void selectionUsesCurrentResultSetPositionAndKeepsRealIdInJavaState() {
        stateService.addResultSet(CONVERSATION, "下周号源", CandidateType.SCHEDULE_SLOT,
                List.of(slot(101L, "上午门诊"), slot(102L, "下午门诊")));
        when(registry.definitions()).thenReturn(List.of());
        PatientSpringAiTools tools = tools();

        String output = find(tools, PatientSpringAiTools.SELECT_CANDIDATE)
                .call("{\"position\":2}", context(contextState()));

        assertThat(output).contains("SELECTED").contains("下午门诊")
                .doesNotContain("102");
        assertThat(stateService.selectedCandidate(CONVERSATION))
                .get().extracting(Candidate::businessId).isEqualTo(102L);
    }

    @Test
    void cancellationPreparationConsumesSelectedAppointmentWithoutModelId() {
        Candidate appointment = new Candidate(
                201L, CandidateType.APPOINTMENT, "陈书宁 2026-10-12 上午门诊",
                2L, "陈书宁", 2L, "消化内科", "2026-10-12",
                null, "上午门诊", "BOOKED", null);
        stateService.addResultSet(CONVERSATION, "我的预约", CandidateType.APPOINTMENT,
                List.of(appointment));
        stateService.selectCandidate(CONVERSATION,
                new com.healthy.agent.state.CandidateSelector(
                        null, 1, null, null, null, null, null, null));
        when(registry.definitions()).thenReturn(List.of());
        PendingActionView pending = pending("action-cancel", PatientActionType.CANCEL_APPOINTMENT);
        when(actionService.prepareCancellation(
                CONVERSATION, appointment, "Bearer secret-token", 8L))
                .thenReturn(ActionPreparationResult.ready(pending));
        when(actionService.confirmationMessage(pending)).thenReturn("请确认取消预约。");
        PatientSpringAiTools tools = tools();

        ToolCallback callback = find(tools, PatientActionService.PREPARE_CANCEL_APPOINTMENT);
        String output = callback.call("{}", context(contextState()));

        assertThat(output).isEqualTo("请确认取消预约。");
        assertThat(callback.getToolDefinition().inputSchema())
                .doesNotContain("appointmentId");
        verify(actionService).prepareCancellation(
                CONVERSATION, appointment, "Bearer secret-token", 8L);
    }

    @Test
    void creationPreparationConsumesSelectedSlotAndRejectsMissingSelection() {
        when(registry.definitions()).thenReturn(List.of());
        PatientSpringAiTools tools = tools();
        ToolCallback create = find(tools, PatientActionService.PREPARE_CREATE_APPOINTMENT);

        String missing = create.call("{}", context(contextState()));
        assertThat(missing).contains("尚未确定唯一号源");
        verify(actionService, never()).prepareCreation(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());

        Candidate slot = slot(101L, "上午门诊");
        stateService.addResultSet(CONVERSATION, "下周号源", CandidateType.SCHEDULE_SLOT,
                List.of(slot));
        stateService.selectCandidate(CONVERSATION,
                new com.healthy.agent.state.CandidateSelector(
                        null, 1, null, null, null, null, null, null));
        PendingActionView pending = pending("action-create", PatientActionType.CREATE_APPOINTMENT);
        when(actionService.prepareCreation(
                CONVERSATION, slot, "Bearer secret-token", 8L))
                .thenReturn(ActionPreparationResult.ready(pending));
        when(actionService.confirmationMessage(pending)).thenReturn("请确认创建预约。");

        assertThat(create.call("{}", context(contextState())))
                .isEqualTo("请确认创建预约。");
        assertThat(create.getToolDefinition().inputSchema())
                .doesNotContain("scheduleSlotId");
    }

    @Test
    void allWaitlistPreparationToolsUseServerSelectedCandidatesAndExposeNoIds() {
        when(registry.definitions()).thenReturn(List.of());
        PatientSpringAiTools tools = tools();

        Candidate fullSlot = new Candidate(
                101L, CandidateType.SCHEDULE_SLOT, "何雨桐 神经内科 2026-10-12 下午门诊 余号 0",
                2L, "何雨桐", 3L, "神经内科", "2026-10-12",
                "AFTERNOON", "下午门诊", null, 0);
        stateService.setSelectedCandidate(CONVERSATION, fullSlot);
        PendingActionView joinPending = pending("join", PatientActionType.JOIN_WAITLIST);
        when(actionService.prepareJoinWaitlist(
                CONVERSATION, fullSlot, "Bearer secret-token", 8L))
                .thenReturn(ActionPreparationResult.ready(joinPending));
        when(actionService.confirmationMessage(joinPending)).thenReturn("请确认加入候补。");

        ToolCallback join = find(tools, PatientActionService.PREPARE_JOIN_WAITLIST);
        assertThat(join.call("{}", context(contextState()))).isEqualTo("请确认加入候补。");
        assertThat(join.getToolDefinition().inputSchema()).doesNotContain("scheduleSlotId");

        Candidate waiting = waitlist("WAITING");
        stateService.setSelectedCandidate(CONVERSATION, waiting);
        PendingActionView cancelPending = pending("cancel", PatientActionType.CANCEL_WAITLIST);
        when(actionService.prepareCancelWaitlist(
                CONVERSATION, waiting, "Bearer secret-token", 8L))
                .thenReturn(ActionPreparationResult.ready(cancelPending));
        when(actionService.confirmationMessage(cancelPending)).thenReturn("请确认取消候补。");

        ToolCallback cancel = find(tools, PatientActionService.PREPARE_CANCEL_WAITLIST);
        assertThat(cancel.call("{}", context(contextState()))).isEqualTo("请确认取消候补。");
        assertThat(cancel.getToolDefinition().inputSchema()).doesNotContain("waitlistId");

        Candidate offered = waitlist("OFFERED");
        stateService.setSelectedCandidate(CONVERSATION, offered);
        PendingActionView confirmPending = pending("confirm", PatientActionType.CONFIRM_WAITLIST);
        when(actionService.prepareConfirmWaitlist(
                CONVERSATION, offered, "Bearer secret-token", 8L))
                .thenReturn(ActionPreparationResult.ready(confirmPending));
        when(actionService.confirmationMessage(confirmPending)).thenReturn("请确认候补名额。");

        ToolCallback confirm = find(tools, PatientActionService.PREPARE_CONFIRM_WAITLIST);
        assertThat(confirm.call("{}", context(contextState()))).isEqualTo("请确认候补名额。");
        assertThat(confirm.getToolDefinition().inputSchema()).doesNotContain("waitlistId");
    }

    @Test
    void ragResultDoesNotCreateBusinessState() {
        when(registry.definitions()).thenReturn(List.of());
        KnowledgeRagResponse rag = new KnowledgeRagResponse(
                "门诊如何退费", "请按原支付渠道办理。[资料1]", "deepseek-flash",
                "BAAI/bge-m3", List.of(), new TokenUsage(50, 10, 60));
        when(ragService.answer(new KnowledgeRagRequest("门诊如何退费", 3))).thenReturn(rag);
        PatientSpringAiTools tools = tools();

        String output = find(tools, PatientSpringAiTools.RAG_TOOL)
                .call("{\"query\":\"门诊如何退费\"}", context(contextState()));

        assertThat(output).isEqualTo(rag.answer());
        assertThat(stateService.getCurrentResultSet(CONVERSATION)).isEmpty();
    }

    private PatientSpringAiTools tools() {
        PatientToolStateAdapter adapter = new PatientToolStateAdapter(stateService, objectMapper);
        PatientResultSetFactory factory = new PatientResultSetFactory(stateService, objectMapper);
        return new PatientSpringAiTools(
                registry, actionService, ragService, stateService, adapter, factory, objectMapper);
    }

    private PatientToolExecutionContext contextState() {
        return new PatientToolExecutionContext(
                "原始问题", "Bearer secret-token", 8L, CONVERSATION);
    }

    private ToolContext context(PatientToolExecutionContext state) {
        return new ToolContext(Map.of(PatientToolExecutionContext.TOOL_CONTEXT_KEY, state));
    }

    private ToolCallback find(PatientSpringAiTools tools, String name) {
        return tools.callbacks().stream()
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst().orElseThrow();
    }

    private Map<String, Object> definition(String name) {
        return Map.of("type", "function", "function", Map.of(
                "name", name, "description", "查询",
                "parameters", Map.of("type", "object", "properties", Map.of())));
    }

    private Candidate slot(long id, String session) {
        return new Candidate(
                id, CandidateType.SCHEDULE_SLOT,
                "苏念安 儿科 2026-10-12 " + session,
                2L, "苏念安", 3L, "儿科", "2026-10-12",
                session.contains("上午") ? "MORNING" : "AFTERNOON",
                session, null, 2);
    }

    private Candidate waitlist(String status) {
        return new Candidate(
                301L, CandidateType.WAITLIST,
                "何雨桐 神经内科 2026-10-12 下午门诊 " + status,
                2L, "何雨桐", 3L, "神经内科", "2026-10-12",
                null, "下午门诊", status, null);
    }

    private PendingActionView pending(String id, PatientActionType type) {
        return new PendingActionView(
                id, type, PatientActionStatus.PENDING,
                new PatientActionPreview(
                        "确认操作", "请核对信息。", "确认", "暂不",
                        List.of(new ActionPreviewField("date", "日期", "2026-10-12"))),
                Instant.parse("2026-10-08T08:00:00Z"));
    }
}
