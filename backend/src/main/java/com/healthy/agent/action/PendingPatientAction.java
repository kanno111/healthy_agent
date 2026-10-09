package com.healthy.agent.action;

import java.time.Instant;

record PendingPatientAction(
        String actionId,
        long userId,
        String conversationId,
        PatientActionType type,
        PatientActionPayload payload,
        PatientActionStatus status,
        Instant createdAt,
        Instant expiresAt,
        PatientActionPreview preview,
        String resultMessage
) {
    PendingPatientAction withStatus(PatientActionStatus nextStatus, String message) {
        return new PendingPatientAction(
                actionId, userId, conversationId, type, payload, nextStatus,
                createdAt, expiresAt, preview, message);
    }

    PendingActionView toView() {
        return new PendingActionView(actionId, type, status, preview, expiresAt);
    }

    PatientActionResponse toResponse() {
        return new PatientActionResponse(actionId, type, status, resultMessage, preview);
    }
}
