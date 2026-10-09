package com.healthy.agent.action;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class InMemoryPendingActionStore {
    private final ConcurrentHashMap<String, PendingPatientAction> actions =
            new ConcurrentHashMap<>();

    PendingPatientAction save(PendingPatientAction action) {
        actions.put(action.actionId(), action);
        return action;
    }

    PendingPatientAction transition(
            String actionId,
            long userId,
            String conversationId,
            PatientActionStatus expected,
            PatientActionStatus next,
            Instant now,
            String message
    ) {
        AtomicReference<PendingPatientAction> updated = new AtomicReference<>();
        AtomicReference<AgentErrorCode> failure = new AtomicReference<>();
        actions.compute(actionId, (ignored, current) -> {
            if (current == null) {
                failure.set(AgentErrorCode.ACTION_NOT_FOUND);
                return null;
            }
            if (current.userId() != userId) {
                failure.set(AgentErrorCode.FORBIDDEN);
                return current;
            }
            if (!current.conversationId().equals(conversationId)) {
                failure.set(AgentErrorCode.FORBIDDEN);
                return current;
            }
            if (current.status() == PatientActionStatus.PENDING
                    && !now.isBefore(current.expiresAt())) {
                PendingPatientAction expired = current.withStatus(
                        PatientActionStatus.EXPIRED, "该确认操作已过期，请重新发起");
                updated.set(expired);
                failure.set(AgentErrorCode.ACTION_EXPIRED);
                return expired;
            }
            if (current.status() != expected) {
                failure.set(AgentErrorCode.ACTION_STATE_CONFLICT);
                return current;
            }
            PendingPatientAction changed = current.withStatus(next, message);
            updated.set(changed);
            return changed;
        });
        if (failure.get() != null) {
            throw new AgentException(failure.get());
        }
        return updated.get();
    }

    PendingPatientAction complete(
            String actionId,
            PatientActionStatus status,
            String message
    ) {
        AtomicReference<PendingPatientAction> updated = new AtomicReference<>();
        actions.computeIfPresent(actionId, (ignored, current) -> {
            if (current.status() != PatientActionStatus.EXECUTING) {
                return current;
            }
            PendingPatientAction changed = current.withStatus(status, message);
            updated.set(changed);
            return changed;
        });
        return Optional.ofNullable(updated.get())
                .orElseThrow(() -> new AgentException(AgentErrorCode.INTERNAL_ERROR));
    }
}
