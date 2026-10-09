package com.healthy.agent.action;

public record CancelWaitlistPayload(long waitlistId) implements PatientActionPayload {
}
