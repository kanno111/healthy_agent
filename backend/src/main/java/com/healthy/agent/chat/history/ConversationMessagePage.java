package com.healthy.agent.chat.history;

import java.util.List;

public record ConversationMessagePage(
        List<ConversationMessageView> messages,
        Long nextBeforeSequence,
        boolean hasMore
) {
    public ConversationMessagePage {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
