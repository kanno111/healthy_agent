package com.healthy.agent.action;

import java.time.Instant;

public record PatientActionContext(
        long userId,
        String authorization,
        Instant now
) {
}
