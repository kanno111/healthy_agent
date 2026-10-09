package com.healthy.agent.state;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;

import java.util.Locale;
import java.util.UUID;

public final class PatientConversationIds {
    private PatientConversationIds() {
    }

    public static String scoped(long userId, String rawConversationId) {
        if (rawConversationId == null) {
            throw new AgentException(AgentErrorCode.INVALID_CONVERSATION_ID);
        }
        String normalized = rawConversationId.strip().toLowerCase(Locale.ROOT);
        try {
            UUID parsed = UUID.fromString(normalized);
            if (!parsed.toString().equals(normalized)) {
                throw new IllegalArgumentException("Non-canonical UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw new AgentException(AgentErrorCode.INVALID_CONVERSATION_ID);
        }
        return "patient:" + userId + ":" + normalized;
    }
}
