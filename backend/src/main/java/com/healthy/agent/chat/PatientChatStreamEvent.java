package com.healthy.agent.chat;

import com.healthy.agent.trace.AgentTrace;

public record PatientChatStreamEvent(
        String type,
        String content,
        PatientChatResponse response,
        Integer code,
        String message,
        String turnId,
        AgentTrace trace
) {
    public static PatientChatStreamEvent delta(String content) {
        return new PatientChatStreamEvent("delta", content, null, null, null, null, null);
    }

    public static PatientChatStreamEvent complete(PatientChatResponse response) {
        return new PatientChatStreamEvent("complete", null, response, null, null,
                response == null ? null : response.turnId(), null);
    }

    public static PatientChatStreamEvent error(int code, String message) {
        return new PatientChatStreamEvent("error", null, null, code, message, null, null);
    }

    public static PatientChatStreamEvent trace(AgentTrace trace) {
        return new PatientChatStreamEvent(
                "trace", null, null, null, null,
                trace == null ? null : trace.turnId(), trace);
    }
}
