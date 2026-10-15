package com.healthy.agent.chat.history;

import com.healthy.agent.chat.PatientChatResponse;

import java.time.Instant;

public record StoredConversationMessage(
        String messageId,
        ConversationRole role,
        String content,
        Instant createdAt,
        PatientChatResponse response
) {
}
