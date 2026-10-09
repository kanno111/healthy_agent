package com.healthy.agent.chat;

import com.healthy.agent.action.PendingActionView;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.knowledge.rag.KnowledgeRagResponse;
import com.healthy.agent.state.CandidateResultSet;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Per-request state passed to tools without exposing credentials to the model. */
public final class PatientToolExecutionContext {
    public static final String TOOL_CONTEXT_KEY = "patientToolExecutionContext";

    private final String question;
    private final String authorization;
    private final long userId;
    private final String conversationId;
    private final Set<String> executedTools = new LinkedHashSet<>();
    private KnowledgeRagResponse ragResponse;
    private CandidateResultSet lastResultSet;
    private PendingActionView pendingAction;
    private String directAnswer;
    private AgentException fatalException;

    public PatientToolExecutionContext(
            String question,
            String authorization,
            long userId,
            String conversationId
    ) {
        this.question = question;
        this.authorization = authorization;
        this.userId = userId;
        this.conversationId = conversationId;
    }

    public String question() { return question; }
    public String authorization() { return authorization; }
    public long userId() { return userId; }
    public String conversationId() { return conversationId; }
    public void recordTool(String name) { executedTools.add(name); }
    public List<String> executedTools() { return List.copyOf(executedTools); }
    public KnowledgeRagResponse ragResponse() { return ragResponse; }
    public void ragResponse(KnowledgeRagResponse value) { ragResponse = value; }
    public CandidateResultSet lastResultSet() { return lastResultSet; }
    public void lastResultSet(CandidateResultSet value) { lastResultSet = value; }
    public PendingActionView pendingAction() { return pendingAction; }
    public void pendingAction(PendingActionView value) { pendingAction = value; }
    public String directAnswer() { return directAnswer; }
    public void directAnswer(String value) { directAnswer = value; }
    public AgentException fatalException() { return fatalException; }
    public void fatalException(AgentException value) { fatalException = value; }
}
