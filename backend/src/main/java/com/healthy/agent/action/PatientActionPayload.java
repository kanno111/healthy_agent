package com.healthy.agent.action;

public sealed interface PatientActionPayload permits CancelAppointmentPayload,
        CreateAppointmentPayload, JoinWaitlistPayload, CancelWaitlistPayload,
        ConfirmWaitlistPayload {
}
