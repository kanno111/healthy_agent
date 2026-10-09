package com.healthy.agent.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healthy.agent.tool.HealthyApiClient;
import com.healthy.agent.tool.ToolError;
import com.healthy.agent.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WaitlistActionHandlersTest {
    private static final String AUTHORIZATION = "Bearer patient-jwt";
    private static final Instant NOW = Instant.parse("2026-10-08T06:00:00Z");
    private static final PatientActionContext CONTEXT =
            new PatientActionContext(8L, AUTHORIZATION, NOW);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HealthyApiClient client = mock(HealthyApiClient.class);

    @Test
    void joiningWaitlistRechecksFullSlotBeforePreparingAndBeforeWriting() {
        JoinWaitlistActionHandler handler = new JoinWaitlistActionHandler(client);
        JoinWaitlistPayload payload = new JoinWaitlistPayload(
                101L, 2L, LocalDate.parse("2026-10-12"));
        when(client.get("/api/user/doctors/2", AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(doctor()));
        when(client.get("/api/user/doctors/2/schedule-slots", dateQuery(), AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(slots(0)));
        when(client.post(eq("/api/user/waitlists"), any(), eq(AUTHORIZATION)))
                .thenReturn(ToolExecutionResult.success(waitlists("WAITING", null).get(0)));

        ActionHandlerPreparationResult<JoinWaitlistPayload> preparation =
                handler.prepare(payload, CONTEXT);

        assertThat(preparation.ready()).isTrue();
        assertThat(preparation.preparedAction().preview().title()).isEqualTo("确认加入候补");
        verify(client, never()).post(eq("/api/user/waitlists"), any(), eq(AUTHORIZATION));

        ActionExecutionResult execution = handler.execute(payload, CONTEXT);

        assertThat(execution.succeeded()).isTrue();
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(client).post(eq("/api/user/waitlists"), body.capture(), eq(AUTHORIZATION));
        assertThat(body.getValue()).isEqualTo(Map.of("scheduleSlotId", 101L));
    }

    @Test
    void joiningWaitlistFailsClosedWhenCapacityHasReturned() {
        JoinWaitlistActionHandler handler = new JoinWaitlistActionHandler(client);
        JoinWaitlistPayload payload = new JoinWaitlistPayload(
                101L, 2L, LocalDate.parse("2026-10-12"));
        when(client.get("/api/user/doctors/2", AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(doctor()));
        when(client.get("/api/user/doctors/2/schedule-slots", dateQuery(), AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(slots(1)));

        ActionHandlerPreparationResult<JoinWaitlistPayload> preparation =
                handler.prepare(payload, CONTEXT);

        assertThat(preparation.ready()).isFalse();
        assertThat(preparation.failure().error().message()).contains("直接预约");
        verify(client, never()).post(eq("/api/user/waitlists"), any(), eq(AUTHORIZATION));
    }

    @Test
    void retryableJoinFailureIsResolvedByReadingLatestWaitlistState() {
        JoinWaitlistActionHandler handler = new JoinWaitlistActionHandler(client);
        JoinWaitlistPayload payload = new JoinWaitlistPayload(
                101L, 2L, LocalDate.parse("2026-10-12"));
        when(client.get("/api/user/doctors/2/schedule-slots", dateQuery(), AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(slots(0)));
        when(client.post(eq("/api/user/waitlists"), any(), eq(AUTHORIZATION)))
                .thenReturn(retryableFailure());
        when(client.get("/api/user/waitlists", AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(waitlists("WAITING", null)));

        ActionExecutionResult execution = handler.execute(payload, CONTEXT);

        assertThat(execution.succeeded()).isTrue();
        assertThat(execution.message()).contains("最新候补记录确认");
    }

    @Test
    void cancellingWaitlistRequiresWaitingAndRechecksBeforePatch() {
        CancelWaitlistActionHandler handler = new CancelWaitlistActionHandler(client);
        CancelWaitlistPayload payload = new CancelWaitlistPayload(301L);
        when(client.get("/api/user/waitlists", AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(waitlists("WAITING", null)));
        when(client.patch("/api/user/waitlists/301/cancel", AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(objectMapper.nullNode()));

        ActionHandlerPreparationResult<CancelWaitlistPayload> preparation =
                handler.prepare(payload, CONTEXT);
        assertThat(preparation.ready()).isTrue();
        verify(client, never()).patch("/api/user/waitlists/301/cancel", AUTHORIZATION);

        ActionExecutionResult execution = handler.execute(payload, CONTEXT);
        assertThat(execution.succeeded()).isTrue();
        verify(client).patch("/api/user/waitlists/301/cancel", AUTHORIZATION);
    }

    @Test
    void confirmingOfferUsesServerExpiryAsShorterCardTtlAndCreatesAppointmentOnlyOnExecute() {
        ConfirmWaitlistActionHandler handler = new ConfirmWaitlistActionHandler(client);
        ConfirmWaitlistPayload payload = new ConfirmWaitlistPayload(301L);
        when(client.get("/api/user/waitlists", AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(
                        waitlists("OFFERED", "2026-10-08T14:03:00")));
        when(client.post("/api/user/waitlists/301/confirm", AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(objectMapper.nullNode()));

        ActionHandlerPreparationResult<ConfirmWaitlistPayload> preparation =
                handler.prepare(payload, CONTEXT);

        assertThat(preparation.ready()).isTrue();
        assertThat(preparation.preparedAction().confirmationTtl())
                .isEqualTo(Duration.ofMinutes(3));
        assertThat(preparation.preparedAction().preview().fields())
                .anyMatch(field -> field.key().equals("offerExpireTime")
                        && field.value().contains("14:03:00"));
        verify(client, never()).post("/api/user/waitlists/301/confirm", AUTHORIZATION);

        ActionExecutionResult execution = handler.execute(payload, CONTEXT);
        assertThat(execution.succeeded()).isTrue();
        assertThat(execution.message()).contains("预约已成功创建");
        verify(client).post("/api/user/waitlists/301/confirm", AUTHORIZATION);
    }

    @Test
    void expiredOfferCannotProduceConfirmationCard() {
        ConfirmWaitlistActionHandler handler = new ConfirmWaitlistActionHandler(client);
        when(client.get("/api/user/waitlists", AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(
                        waitlists("OFFERED", "2026-10-08T13:59:59")));

        ActionHandlerPreparationResult<ConfirmWaitlistPayload> preparation =
                handler.prepare(new ConfirmWaitlistPayload(301L), CONTEXT);

        assertThat(preparation.ready()).isFalse();
        assertThat(preparation.failure().error().message()).contains("已经结束");
        verify(client, never()).post("/api/user/waitlists/301/confirm", AUTHORIZATION);
    }

    private ObjectNode doctor() {
        return objectMapper.createObjectNode()
                .put("id", 2L).put("name", "何雨桐").put("departmentName", "神经内科");
    }

    private ArrayNode slots(int remainingCapacity) {
        return objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("id", 101L).put("scheduleDate", "2026-10-12")
                .put("sessionName", "下午门诊")
                .put("startTime", "14:00:00").put("endTime", "17:00:00")
                .put("remainingCapacity", remainingCapacity));
    }

    private ArrayNode waitlists(String status, String offerExpireTime) {
        ObjectNode waitlist = objectMapper.createObjectNode()
                .put("id", 301L).put("scheduleSlotId", 101L)
                .put("doctorId", 2L).put("doctorName", "何雨桐")
                .put("departmentId", 3L).put("departmentName", "神经内科")
                .put("scheduleDate", "2026-10-12").put("sessionName", "下午门诊")
                .put("startTime", "14:00:00").put("endTime", "17:00:00")
                .put("status", status);
        if (offerExpireTime != null) waitlist.put("offerExpireTime", offerExpireTime);
        return objectMapper.createArrayNode().add(waitlist);
    }

    private Map<String, Object> dateQuery() {
        LocalDate date = LocalDate.parse("2026-10-12");
        return Map.of("startDate", date, "endDate", date);
    }

    private ToolExecutionResult retryableFailure() {
        return ToolExecutionResult.failure(new ToolError(
                503, 50300, "DEPENDENCY_UNAVAILABLE", "医院业务服务暂时不可用", true));
    }
}
