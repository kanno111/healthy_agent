package com.healthy.agent.action;

public record ConfirmWaitlistPayload(long waitlistId) implements PatientActionPayload {
}
