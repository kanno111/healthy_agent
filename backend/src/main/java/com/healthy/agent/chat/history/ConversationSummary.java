package com.healthy.agent.chat.history;

import java.time.Instant;

public record ConversationSummary(
        String conversationId,
        String title,
        Instant createdAt,
        Instant updatedAt,
        long messageCount
) {
}
