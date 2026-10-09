package com.healthy.agent.action;

import java.time.Instant;

public record PendingActionView(
        String actionId,
        PatientActionType type,
        PatientActionStatus status,
        PatientActionPreview preview,
        Instant expiresAt
) {
}
