package com.healthy.agent.knowledge.rag;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/** Optional patient-conversation context used only to create a standalone retrieval query. */
public record KnowledgeQueryRewriteContext(
        String currentQuestion,
        String conversationSummary,
        List<Message> recentMessages
) {
    public KnowledgeQueryRewriteContext {
        currentQuestion = currentQuestion == null ? "" : currentQuestion.strip();
        conversationSummary = conversationSummary == null ? "" : conversationSummary.strip();
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
    }

    public static KnowledgeQueryRewriteContext empty() {
        return new KnowledgeQueryRewriteContext("", "", List.of());
    }
}
