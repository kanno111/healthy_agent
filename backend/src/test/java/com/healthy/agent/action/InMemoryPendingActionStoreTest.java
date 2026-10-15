package com.healthy.agent.action;

import com.healthy.agent.common.AgentException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryPendingActionStoreTest {
    @Test
    void deletingConversationMakesPendingActionUnconfirmable() {
        InMemoryPendingActionStore store = new InMemoryPendingActionStore();
        Instant now = Instant.parse("2026-10-10T02:00:00Z");
        store.save(new PendingPatientAction(
                "action-1", 8L,
                "patient:8:11111111-1111-4111-8111-111111111111",
                PatientActionType.CANCEL_APPOINTMENT,
                new CancelAppointmentPayload(100L),
                PatientActionStatus.PENDING, now, now.plusSeconds(600),
                new PatientActionPreview(
                        "确认取消", "请确认", "确认", "放弃", List.of()),
                "等待确认"));

        store.discardConversation(
                8L, "patient:8:11111111-1111-4111-8111-111111111111");

        assertThatThrownBy(() -> store.transition(
                "action-1", 8L,
                "patient:8:11111111-1111-4111-8111-111111111111",
                PatientActionStatus.PENDING, PatientActionStatus.EXECUTING,
                now, "执行"))
                .isInstanceOf(AgentException.class)
                .extracting("errorCode.code").isEqualTo(40420);
    }
}
