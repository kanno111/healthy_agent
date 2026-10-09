package com.healthy.agent.chat;

import com.healthy.agent.action.PendingActionView;
import com.healthy.agent.action.PatientActionResponse;
import com.healthy.agent.knowledge.rag.KnowledgeRagCitation;
import com.healthy.agent.llm.TokenUsage;

import java.util.List;

public record PatientChatResponse(
        String question,
        String answer,
        String mode,
        String model,
        String embeddingModel,
        List<KnowledgeRagCitation> citations,
        TokenUsage usage,
        List<String> tools,
        PendingActionView pendingAction,
        PatientActionResponse actionUpdate
) {
    public PatientChatResponse(
            String question,
            String answer,
            String mode,
            String model,
            String embeddingModel,
            List<KnowledgeRagCitation> citations,
            TokenUsage usage,
            List<String> tools
    ) {
        this(question, answer, mode, model, embeddingModel, citations, usage, tools, null, null);
    }

    public PatientChatResponse(
            String question,
            String answer,
            String mode,
            String model,
            String embeddingModel,
            List<KnowledgeRagCitation> citations,
            TokenUsage usage,
            List<String> tools,
            PendingActionView pendingAction
    ) {
        this(question, answer, mode, model, embeddingModel, citations, usage,
                tools, pendingAction, null);
    }

    public PatientChatResponse {
        citations = citations == null ? List.of() : List.copyOf(citations);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
