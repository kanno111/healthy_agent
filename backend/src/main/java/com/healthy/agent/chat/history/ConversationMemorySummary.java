package com.healthy.agent.chat.history;

import java.time.Instant;

/** Redis-backed rolling summary for one patient conversation. */
public record ConversationMemorySummary(
        String content,
        long summarizedThroughSequence,
        Instant updatedAt
) {
    public static ConversationMemorySummary empty() {
        return new ConversationMemorySummary("", 0L, null);
    }

    public boolean present() {
        return content != null && !content.isBlank();
    }
}
