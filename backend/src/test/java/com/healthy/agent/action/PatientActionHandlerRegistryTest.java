package com.healthy.agent.action;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatientActionHandlerRegistryTest {
    private final PatientActionContext context = new PatientActionContext(
            8L, "Bearer token", Instant.parse("2026-10-08T06:00:00Z"));

    @Test
    void routesPreparationAndExecutionToHandlerRegisteredForActionType() {
        StubHandler handler = new StubHandler();
        PatientActionHandlerRegistry registry = new PatientActionHandlerRegistry(
                List.of(handler));
        CancelAppointmentPayload payload = new CancelAppointmentPayload(201L);

        ActionHandlerPreparationResult<?> preparation = registry.prepare(
                PatientActionType.CANCEL_APPOINTMENT, payload, context);
        ActionExecutionResult execution = registry.execute(
                PatientActionType.CANCEL_APPOINTMENT, payload, context);

        assertThat(preparation.ready()).isTrue();
        assertThat(preparation.preparedAction().payload()).isEqualTo(payload);
        assertThat(execution.succeeded()).isTrue();
        assertThat(handler.prepareCalls).isEqualTo(1);
        assertThat(handler.executeCalls).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateHandlerRegistrationAtStartup() {
        assertThatThrownBy(() -> new PatientActionHandlerRegistry(
                List.of(new StubHandler(), new StubHandler())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate patient action handler");
    }

    @Test
    void failsClosedWhenActionTypeHasNoHandler() {
        PatientActionHandlerRegistry registry = new PatientActionHandlerRegistry(
                List.of(new StubHandler()));

        assertThatThrownBy(() -> registry.execute(
                PatientActionType.CREATE_APPOINTMENT,
                new CancelAppointmentPayload(201L),
                context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No patient action handler registered");
    }

    private static final class StubHandler
            implements PatientActionHandler<CancelAppointmentPayload> {
        private int prepareCalls;
        private int executeCalls;

        @Override
        public PatientActionType type() {
            return PatientActionType.CANCEL_APPOINTMENT;
        }

        @Override
        public Class<CancelAppointmentPayload> payloadType() {
            return CancelAppointmentPayload.class;
        }

        @Override
        public ActionHandlerPreparationResult<CancelAppointmentPayload> prepare(
                CancelAppointmentPayload payload,
                PatientActionContext context
        ) {
            prepareCalls++;
            PatientActionPreview preview = new PatientActionPreview(
                    "确认操作", "请确认", "确认", "放弃", List.of());
            return ActionHandlerPreparationResult.ready(new PreparedPatientAction<>(
                    payload, preview, Duration.ofMinutes(10)));
        }

        @Override
        public ActionExecutionResult execute(
                CancelAppointmentPayload payload,
                PatientActionContext context
        ) {
            executeCalls++;
            return ActionExecutionResult.succeeded("完成");
        }
    }
}
