package com.healthy.agent.action;

import com.healthy.agent.trace.AgentTrace;

public record PatientActionResponse(
        String actionId,
        PatientActionType type,
        PatientActionStatus status,
        String message,
        PatientActionPreview preview,
        String turnId,
        AgentTrace trace
) {
    public PatientActionResponse(
            String actionId,
            PatientActionType type,
            PatientActionStatus status,
            String message,
            PatientActionPreview preview
    ) {
        this(actionId, type, status, message, preview, null, null);
    }

    public PatientActionResponse withTrace(AgentTrace value) {
        return new PatientActionResponse(
                actionId, type, status, message, preview,
                value == null ? null : value.turnId(), value);
    }
}
