package com.healthy.agent.chat.history;

/** A minimal, ordered history message used only while compacting old chat turns. */
public record ConversationMemoryMessage(
        long sequence,
        ConversationRole role,
        String content
) {
}
