package com.healthy.agent.chat.history;

import java.util.List;

/** The next complete turns that have fallen outside the raw model-message window. */
public record ConversationSummaryBatch(
        List<ConversationMemoryMessage> messages,
        long throughSequence
) {
    public ConversationSummaryBatch {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public static ConversationSummaryBatch empty(long throughSequence) {
        return new ConversationSummaryBatch(List.of(), throughSequence);
    }

    public boolean empty() {
        return messages.isEmpty();
    }
}
