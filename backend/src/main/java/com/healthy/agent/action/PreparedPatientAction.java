package com.healthy.agent.action;

import java.time.Duration;

public record PreparedPatientAction<P extends PatientActionPayload>(
        P payload,
        PatientActionPreview preview,
        Duration confirmationTtl
) {
}
