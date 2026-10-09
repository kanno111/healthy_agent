package com.healthy.agent.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthy.agent.tool.HealthyApiClient;
import com.healthy.agent.tool.ToolError;
import com.healthy.agent.tool.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateAppointmentActionHandlerTest {
    private static final String AUTHORIZATION = "Bearer patient-jwt";
    private static final LocalDate SCHEDULE_DATE = LocalDate.parse("2026-10-12");
    private static final Map<String, Object> DATE_QUERY = Map.of(
            "startDate", SCHEDULE_DATE,
            "endDate", SCHEDULE_DATE);

    private final HealthyApiClient client = mock(HealthyApiClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CreateAppointmentPayload payload = new CreateAppointmentPayload(
            101L, 2L, SCHEDULE_DATE, "request-101");
    private final PatientActionContext context = new PatientActionContext(
            8L, AUTHORIZATION, Instant.parse("2026-10-08T06:00:00Z"));
    private CreateAppointmentActionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CreateAppointmentActionHandler(client);
    }

    @Test
    void preparationRechecksSlotAndBuildsPreviewWithoutCreatingAppointment() {
        stubDoctorAndAvailableSlot();

        ActionHandlerPreparationResult<CreateAppointmentPayload> result =
                handler.prepare(payload, context);

        assertThat(result.ready()).isTrue();
        assertThat(result.preparedAction().payload()).isEqualTo(payload);
        assertThat(result.preparedAction().preview().fields())
                .contains(
                        new ActionPreviewField("doctorName", "医生", "苏念安"),
                        new ActionPreviewField("departmentName", "科室", "儿科"),
                        new ActionPreviewField("time", "时间", "08:00–12:00"));
        verify(client, never()).post(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void confirmationRechecksAvailabilityAndReusesRequestIdOnRetry() {
        when(client.get(
                "/api/user/doctors/2/schedule-slots", DATE_QUERY, AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(availableSlots()));
        Map<String, Object> expectedBody = Map.of(
                "scheduleSlotId", 101L,
                "requestId", "request-101");
        when(client.post(
                "/api/user/appointments", expectedBody, AUTHORIZATION))
                .thenReturn(
                        ToolExecutionResult.failure(new ToolError(
                                503, 50300, "DEPENDENCY_UNAVAILABLE",
                                "网络超时", true)),
                        ToolExecutionResult.success(objectMapper.createObjectNode()
                                .put("id", 201L)));

        ActionExecutionResult result = handler.execute(payload, context);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.message()).contains("成功创建");
        verify(client, times(2)).post(
                "/api/user/appointments", expectedBody, AUTHORIZATION);
    }

    @Test
    void fullSlotFailsBeforePost() {
        var slots = availableSlots();
        ((com.fasterxml.jackson.databind.node.ObjectNode) slots.get(0))
                .put("remainingCapacity", 0);
        when(client.get(
                "/api/user/doctors/2/schedule-slots", DATE_QUERY, AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(slots));

        ActionExecutionResult result = handler.execute(payload, context);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.message()).contains("没有剩余号源");
        verify(client, never()).post(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private void stubDoctorAndAvailableSlot() {
        when(client.get("/api/user/doctors/2", AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(objectMapper.createObjectNode()
                        .put("id", 2L)
                        .put("name", "苏念安")
                        .put("departmentName", "儿科")));
        when(client.get(
                "/api/user/doctors/2/schedule-slots", DATE_QUERY, AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(availableSlots()));
    }

    private com.fasterxml.jackson.databind.node.ArrayNode availableSlots() {
        return objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("id", 101L)
                .put("scheduleDate", "2026-10-12")
                .put("sessionName", "上午门诊")
                .put("startTime", "08:00:00")
                .put("endTime", "12:00:00")
                .put("remainingCapacity", 3));
    }
}
