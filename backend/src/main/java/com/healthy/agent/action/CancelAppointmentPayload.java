package com.healthy.agent.action;

public record CancelAppointmentPayload(long appointmentId) implements PatientActionPayload {
}
