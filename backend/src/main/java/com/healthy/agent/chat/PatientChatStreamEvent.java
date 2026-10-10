package com.healthy.agent.chat;

public record PatientChatStreamEvent(
        String type,
        String content,
        PatientChatResponse response,
        Integer code,
        String message
) {
    public static PatientChatStreamEvent delta(String content) {
        return new PatientChatStreamEvent("delta", content, null, null, null);
    }

    public static PatientChatStreamEvent complete(PatientChatResponse response) {
        return new PatientChatStreamEvent("complete", null, response, null, null);
    }

    public static PatientChatStreamEvent error(int code, String message) {
        return new PatientChatStreamEvent("error", null, null, code, message);
    }
}
