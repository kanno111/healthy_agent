package com.healthy.agent.chat.history;

import com.healthy.agent.chat.PatientChatResponse;

import java.time.Instant;

public record ConversationMessageView(
        long sequence,
        String messageId,
        ConversationRole role,
        String content,
        Instant createdAt,
        PatientChatResponse response
) {
}
