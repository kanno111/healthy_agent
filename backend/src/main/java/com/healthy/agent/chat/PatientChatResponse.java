package com.healthy.agent.chat;

import com.healthy.agent.action.PendingActionView;
import com.healthy.agent.action.PatientActionResponse;
import com.healthy.agent.knowledge.rag.KnowledgeRagCitation;
import com.healthy.agent.llm.TokenUsage;
import com.healthy.agent.trace.AgentTrace;

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
        PatientActionResponse actionUpdate,
        String turnId,
        AgentTrace trace
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
        this(question, answer, mode, model, embeddingModel, citations, usage, tools,
                null, null, null, null);
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
                tools, pendingAction, null, null, null);
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
            PendingActionView pendingAction,
            PatientActionResponse actionUpdate
    ) {
        this(question, answer, mode, model, embeddingModel, citations, usage,
                tools, pendingAction, actionUpdate, null, null);
    }

    public PatientChatResponse {
        citations = citations == null ? List.of() : List.copyOf(citations);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public PatientChatResponse withTrace(AgentTrace value) {
        return new PatientChatResponse(
                question, answer, mode, model, embeddingModel, citations, usage, tools,
                pendingAction, actionUpdate,
                value == null ? null : value.turnId(), value);
    }

    public PatientChatResponse withUsage(TokenUsage value) {
        return new PatientChatResponse(
                question, answer, mode, model, embeddingModel, citations, value, tools,
                pendingAction, actionUpdate, turnId, trace);
    }

    public PatientChatResponse withoutTracePayload() {
        return new PatientChatResponse(
                question, answer, mode, model, embeddingModel, citations, usage, tools,
                pendingAction, actionUpdate, turnId, null);
    }
}
