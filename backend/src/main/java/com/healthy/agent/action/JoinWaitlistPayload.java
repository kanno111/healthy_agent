package com.healthy.agent.action;

import java.time.LocalDate;

public record JoinWaitlistPayload(
        long scheduleSlotId,
        long doctorId,
        LocalDate scheduleDate
) implements PatientActionPayload {
}
