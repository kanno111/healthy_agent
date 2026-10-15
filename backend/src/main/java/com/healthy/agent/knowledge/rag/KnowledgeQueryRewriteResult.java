package com.healthy.agent.knowledge.rag;

import com.healthy.agent.llm.TokenUsage;

public record KnowledgeQueryRewriteResult(
        String query,
        boolean changed,
        boolean fallback,
        String model,
        TokenUsage usage
) {
    public KnowledgeQueryRewriteResult {
        usage = usage == null ? TokenUsage.empty() : usage;
    }
}
