package com.healthy.agent.action;

public record PatientActionResponse(
        String actionId,
        PatientActionType type,
        PatientActionStatus status,
        String message,
        PatientActionPreview preview
) {
}
