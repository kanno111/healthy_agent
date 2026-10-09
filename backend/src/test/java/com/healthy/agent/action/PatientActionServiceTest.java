package com.healthy.agent.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.tool.HealthyApiClient;
import com.healthy.agent.tool.ToolExecutionResult;
import com.healthy.agent.state.AgentStateService;
import com.healthy.agent.state.AgentTaskType;
import com.healthy.agent.state.Candidate;
import com.healthy.agent.state.CandidateResultSet;
import com.healthy.agent.state.CandidateType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatientActionServiceTest {
    private static final String AUTHORIZATION = "Bearer patient-jwt";
    private static final String CONVERSATION =
            "patient:8:11111111-1111-4111-8111-111111111111";
    private final HealthyApiClient healthyApiClient = mock(HealthyApiClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private InMemoryPendingActionStore store;
    private AgentStateService stateService;
    private PatientActionService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryPendingActionStore();
        Clock fixedClock = Clock.fixed(
                Instant.parse("2026-10-08T06:00:00Z"), ZoneOffset.UTC);
        stateService = new AgentStateService(fixedClock);
        PatientActionHandlerRegistry handlerRegistry = new PatientActionHandlerRegistry(
                List.of(
                        new CancelAppointmentActionHandler(healthyApiClient),
                        new CreateAppointmentActionHandler(healthyApiClient),
                        new JoinWaitlistActionHandler(healthyApiClient),
                        new CancelWaitlistActionHandler(healthyApiClient),
                        new ConfirmWaitlistActionHandler(healthyApiClient)));
        service = new PatientActionService(
                handlerRegistry,
                store,
                stateService,
                fixedClock);
    }

    @Test
    void preparationDoesNotCancelAndConfirmationRechecksThenPatches() {
        ToolExecutionResult appointments = ToolExecutionResult.success(appointments("BOOKED"));
        when(healthyApiClient.get("/api/user/appointments", AUTHORIZATION))
                .thenReturn(appointments);
        when(healthyApiClient.patch(
                "/api/user/appointments/201/cancel", AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(objectMapper.nullNode()));

        ActionPreparationResult preparation = service.prepareCancellation(
                CONVERSATION, appointmentCandidate(), AUTHORIZATION, 8L);

        assertThat(preparation.ready()).isTrue();
        assertThat(preparation.pendingAction().status()).isEqualTo(PatientActionStatus.PENDING);
        assertThat(preparation.pendingAction().preview().fields())
                .contains(new ActionPreviewField("doctorName", "医生", "陈书宁"));
        verify(healthyApiClient, never()).patch(
                "/api/user/appointments/201/cancel", AUTHORIZATION);

        PatientActionResponse confirmed = service.confirm(
                preparation.pendingAction().actionId(), CONVERSATION, 8L, AUTHORIZATION);

        assertThat(confirmed.status()).isEqualTo(PatientActionStatus.SUCCEEDED);
        assertThat(confirmed.message()).contains("成功取消");
        assertThat(stateService.lastActionResult(CONVERSATION))
                .get().extracting(PatientActionResponse::status)
                .isEqualTo(PatientActionStatus.SUCCEEDED);
        verify(healthyApiClient).patch(
                "/api/user/appointments/201/cancel", AUTHORIZATION);

        assertThatThrownBy(() -> service.confirm(
                preparation.pendingAction().actionId(), CONVERSATION, 8L, AUTHORIZATION))
                .isInstanceOfSatisfying(AgentException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(AgentErrorCode.ACTION_STATE_CONFLICT));
    }

    @Test
    void rejectionNeverCallsCancellationEndpoint() {
        when(healthyApiClient.get("/api/user/appointments", AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(appointments("BOOKED")));
        ActionPreparationResult preparation = service.prepareCancellation(
                CONVERSATION, appointmentCandidate(), AUTHORIZATION, 8L);

        PatientActionResponse rejected = service.reject(
                preparation.pendingAction().actionId(), CONVERSATION, 8L);

        assertThat(rejected.status()).isEqualTo(PatientActionStatus.REJECTED);
        assertThat(stateService.lastActionResult(CONVERSATION))
                .get().extracting(PatientActionResponse::status)
                .isEqualTo(PatientActionStatus.REJECTED);
        verify(healthyApiClient, never()).patch(
                "/api/user/appointments/201/cancel", AUTHORIZATION);
    }

    @Test
    void anotherUserCannotConfirmAction() {
        when(healthyApiClient.get("/api/user/appointments", AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(appointments("BOOKED")));
        ActionPreparationResult preparation = service.prepareCancellation(
                CONVERSATION, appointmentCandidate(), AUTHORIZATION, 8L);

        assertThatThrownBy(() -> service.confirm(
                preparation.pendingAction().actionId(), CONVERSATION, 9L, AUTHORIZATION))
                .isInstanceOfSatisfying(AgentException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(AgentErrorCode.FORBIDDEN));
        verify(healthyApiClient, never()).patch(
                "/api/user/appointments/201/cancel", AUTHORIZATION);
    }

    @Test
    void sameUserCannotConfirmActionFromAnotherConversation() {
        when(healthyApiClient.get("/api/user/appointments", AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(appointments("BOOKED")));
        ActionPreparationResult preparation = service.prepareCancellation(
                CONVERSATION, appointmentCandidate(), AUTHORIZATION, 8L);

        assertThatThrownBy(() -> service.confirm(
                preparation.pendingAction().actionId(),
                "patient:8:22222222-2222-4222-8222-222222222222",
                8L, AUTHORIZATION))
                .isInstanceOfSatisfying(AgentException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(AgentErrorCode.FORBIDDEN));
    }

    @Test
    void duplicatePreparationReusesOnePendingAction() {
        when(healthyApiClient.get("/api/user/appointments", AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(appointments("BOOKED")));

        ActionPreparationResult first = service.prepareCancellation(
                CONVERSATION, appointmentCandidate(), AUTHORIZATION, 8L);
        ActionPreparationResult second = service.prepareCancellation(
                CONVERSATION, appointmentCandidate(), AUTHORIZATION, 8L);

        assertThat(second.pendingAction().actionId())
                .isEqualTo(first.pendingAction().actionId());
        verify(healthyApiClient).get("/api/user/appointments", AUTHORIZATION);
    }

    @Test
    void confirmationTextKeepsPreviewFieldsInChatMemoryWithoutBusinessIds() {
        PendingActionView view = new PendingActionView(
                "action-1", PatientActionType.CREATE_APPOINTMENT,
                PatientActionStatus.PENDING,
                new PatientActionPreview(
                        "确认创建预约", "创建预约属于写操作，请核对后确认。",
                        "确认预约", "暂不预约",
                        List.of(
                                new ActionPreviewField("doctorName", "医生", "何雨桐"),
                                new ActionPreviewField("scheduleDate", "日期", "2026-10-12"),
                                new ActionPreviewField("sessionName", "时段", "下午门诊"))),
                Instant.parse("2026-10-08T06:10:00Z"));

        assertThat(service.confirmationMessage(view))
                .contains("医生：何雨桐", "日期：2026-10-12", "时段：下午门诊")
                .contains("尚未执行", "确认预约")
                .doesNotContain("action-1", "scheduleSlotId", "appointmentId");
    }

    @Test
    void expiredActionCannotBeConfirmed() {
        when(healthyApiClient.get("/api/user/appointments", AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(appointments("BOOKED")));
        ActionPreparationResult preparation = service.prepareCancellation(
                CONVERSATION, appointmentCandidate(), AUTHORIZATION, 8L);
        PendingActionView pendingAction = preparation.pendingAction();

        assertThatThrownBy(() -> store.transition(
                pendingAction.actionId(), 8L, CONVERSATION,
                PatientActionStatus.PENDING, PatientActionStatus.EXECUTING,
                pendingAction.expiresAt().plus(Duration.ofSeconds(1)), "正在执行"))
                .isInstanceOfSatisfying(AgentException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(AgentErrorCode.ACTION_EXPIRED));
        verify(healthyApiClient, never()).patch(
                "/api/user/appointments/201/cancel", AUTHORIZATION);
    }

    @Test
    void creationGeneratesHiddenRequestIdAndUsesItOnlyAfterConfirmation() {
        var doctor = objectMapper.createObjectNode()
                .put("id", 2L)
                .put("name", "苏念安")
                .put("departmentName", "儿科");
        var slots = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("id", 101L)
                .put("scheduleDate", "2026-10-12")
                .put("sessionName", "上午门诊")
                .put("startTime", "08:00:00")
                .put("endTime", "12:00:00")
                .put("remainingCapacity", 2));
        Map<String, Object> dateQuery = Map.of(
                "startDate", java.time.LocalDate.parse("2026-10-12"),
                "endDate", java.time.LocalDate.parse("2026-10-12"));
        when(healthyApiClient.get("/api/user/doctors/2", AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(doctor));
        when(healthyApiClient.get(
                "/api/user/doctors/2/schedule-slots", dateQuery, AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(slots));
        when(healthyApiClient.post(
                org.mockito.ArgumentMatchers.eq("/api/user/appointments"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(AUTHORIZATION)))
                .thenReturn(ToolExecutionResult.success(
                        objectMapper.createObjectNode().put("id", 301L)));

        Candidate slotCandidate = slotCandidate();
        CandidateResultSet source = stateService.addResultSet(
                CONVERSATION, "下周号源", CandidateType.SCHEDULE_SLOT,
                List.of(slotCandidate));
        stateService.setSelectedCandidate(CONVERSATION, slotCandidate);
        ActionPreparationResult preparation = service.prepareCreation(
                CONVERSATION, slotCandidate, AUTHORIZATION, 8L);

        assertThat(preparation.ready()).isTrue();
        assertThat(preparation.pendingAction().type())
                .isEqualTo(PatientActionType.CREATE_APPOINTMENT);
        verify(healthyApiClient, never()).post(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());

        PatientActionResponse response = service.confirm(
                preparation.pendingAction().actionId(), CONVERSATION, 8L, AUTHORIZATION);

        assertThat(response.status()).isEqualTo(PatientActionStatus.SUCCEEDED);
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(healthyApiClient).post(
                org.mockito.ArgumentMatchers.eq("/api/user/appointments"),
                body.capture(),
                org.mockito.ArgumentMatchers.eq(AUTHORIZATION));
        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) body.getValue();
        assertThat(request.get("scheduleSlotId")).isEqualTo(101L);
        assertThatCode(() -> UUID.fromString((String) request.get("requestId")))
                .doesNotThrowAnyException();
        assertThat(preparation.pendingAction().preview().toString())
                .doesNotContain((String) request.get("requestId"));
        assertThat(stateService.getPendingAction(CONVERSATION)).isEmpty();
        assertThat(stateService.selectedCandidate(CONVERSATION)).isEmpty();
        assertThat(stateService.findRecentResultSet(CONVERSATION, source.resultSetId()))
                .isPresent();
    }

    @Test
    void joinWaitlistPreparationUsesUnifiedPendingActionWithoutWriting() {
        var doctor = objectMapper.createObjectNode()
                .put("id", 2L).put("name", "何雨桐").put("departmentName", "神经内科");
        var slots = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("id", 101L).put("scheduleDate", "2026-10-12")
                .put("sessionName", "下午门诊")
                .put("startTime", "14:00:00").put("endTime", "17:00:00")
                .put("remainingCapacity", 0));
        Map<String, Object> dateQuery = Map.of(
                "startDate", java.time.LocalDate.parse("2026-10-12"),
                "endDate", java.time.LocalDate.parse("2026-10-12"));
        when(healthyApiClient.get("/api/user/doctors/2", AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(doctor));
        when(healthyApiClient.get(
                "/api/user/doctors/2/schedule-slots", dateQuery, AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(slots));
        Candidate fullSlot = new Candidate(
                101L, CandidateType.SCHEDULE_SLOT,
                "何雨桐 神经内科 2026-10-12 下午门诊 余号 0",
                2L, "何雨桐", 3L, "神经内科", "2026-10-12",
                "AFTERNOON", "下午门诊", null, 0);
        stateService.setSelectedCandidate(CONVERSATION, fullSlot);

        ActionPreparationResult preparation = service.prepareJoinWaitlist(
                CONVERSATION, fullSlot, AUTHORIZATION, 8L);

        assertThat(preparation.ready()).isTrue();
        assertThat(preparation.pendingAction().type()).isEqualTo(PatientActionType.JOIN_WAITLIST);
        assertThat(stateService.getOrCreate(CONVERSATION).phase())
                .isEqualTo(com.healthy.agent.state.AgentPhase.WAITING_CONFIRMATION);
        assertThat(stateService.getOrCreate(CONVERSATION).activeTask())
                .isEqualTo(AgentTaskType.JOIN_WAITLIST);
        assertThat(preparation.pendingAction().toString())
                .doesNotContain("scheduleSlotId");
        verify(healthyApiClient, never()).post(
                org.mockito.ArgumentMatchers.eq("/api/user/waitlists"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(AUTHORIZATION));
    }

    private ArrayNode appointments(String status) {
        ObjectNode appointment = objectMapper.createObjectNode();
        appointment.put("id", 201L);
        appointment.put("appointmentNo", "A201");
        appointment.put("doctorId", 2L);
        appointment.put("doctorName", "陈书宁");
        appointment.put("departmentId", 2L);
        appointment.put("departmentName", "消化内科");
        appointment.put("scheduleDate", "2026-10-09");
        appointment.put("sessionName", "下午门诊");
        appointment.put("startTime", "14:00:00");
        appointment.put("endTime", "17:00:00");
        appointment.put("status", status);
        return objectMapper.createArrayNode().add(appointment);
    }

    private Candidate appointmentCandidate() {
        return new Candidate(
                201L, CandidateType.APPOINTMENT,
                "陈书宁 消化内科 2026-10-09 下午门诊",
                2L, "陈书宁", 2L, "消化内科", "2026-10-09",
                null, "下午门诊", "BOOKED", null);
    }

    private Candidate slotCandidate() {
        return new Candidate(
                101L, CandidateType.SCHEDULE_SLOT,
                "苏念安 儿科 2026-10-12 上午门诊",
                2L, "苏念安", 3L, "儿科", "2026-10-12",
                "MORNING", "上午门诊", null, 2);
    }
}
