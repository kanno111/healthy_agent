package com.healthy.agent.action;

import java.time.LocalDate;

public record CreateAppointmentPayload(
        long scheduleSlotId,
        long doctorId,
        LocalDate scheduleDate,
        String requestId
) implements PatientActionPayload {
}
