package com.healthy.agent.chat;

import com.healthy.agent.action.PendingActionView;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.knowledge.rag.KnowledgeRagResponse;
import com.healthy.agent.state.CandidateResultSet;
import com.healthy.agent.trace.AgentTraceRecorder;
import org.springframework.ai.chat.messages.Message;

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
    private final AgentTraceRecorder trace;
    private final Set<String> executedTools = new LinkedHashSet<>();
    private KnowledgeRagResponse ragResponse;
    private CandidateResultSet lastResultSet;
    private PendingActionView pendingAction;
    private String directAnswer;
    private AgentException fatalException;
    private String conversationSummary = "";
    private List<Message> recentConversationMessages = List.of();

    public PatientToolExecutionContext(
            String question,
            String authorization,
            long userId,
            String conversationId
    ) {
        this(question, authorization, userId, conversationId, AgentTraceRecorder.noop());
    }

    public PatientToolExecutionContext(
            String question,
            String authorization,
            long userId,
            String conversationId,
            AgentTraceRecorder trace
    ) {
        this.question = question;
        this.authorization = authorization;
        this.userId = userId;
        this.conversationId = conversationId;
        this.trace = trace == null ? AgentTraceRecorder.noop() : trace;
    }

    public String question() { return question; }
    public String authorization() { return authorization; }
    public long userId() { return userId; }
    public String conversationId() { return conversationId; }
    public AgentTraceRecorder trace() { return trace; }
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
    public String conversationSummary() { return conversationSummary; }
    public List<Message> recentConversationMessages() { return recentConversationMessages; }
    public void conversationMemory(String summary, List<Message> recentMessages) {
        conversationSummary = summary == null ? "" : summary.strip();
        recentConversationMessages = recentMessages == null
                ? List.of() : List.copyOf(recentMessages);
    }
}
