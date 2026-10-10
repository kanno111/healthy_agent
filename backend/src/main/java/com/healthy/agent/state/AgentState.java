package com.healthy.agent.state;

import com.healthy.agent.action.PendingActionView;
import com.healthy.agent.action.PatientActionResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Mutable only inside AgentStateService map computations. */
public final class AgentState {
    private final String conversationId;
    private String currentResultSetId;
    private final List<CandidateResultSet> recentResultSets;
    private Candidate selectedCandidate;
    private PendingActionView pendingAction;
    private PatientActionResponse lastActionResult;
    private Instant expiresAt;

    AgentState(String conversationId, Instant expiresAt) {
        this(conversationId, null, new ArrayList<>(), null, null, null, expiresAt);
    }

    private AgentState(
            String conversationId,
            String currentResultSetId,
            List<CandidateResultSet> recentResultSets,
            Candidate selectedCandidate,
            PendingActionView pendingAction,
            PatientActionResponse lastActionResult,
            Instant expiresAt
    ) {
        this.conversationId = conversationId;
        this.currentResultSetId = currentResultSetId;
        this.recentResultSets = recentResultSets;
        this.selectedCandidate = selectedCandidate;
        this.pendingAction = pendingAction;
        this.lastActionResult = lastActionResult;
        this.expiresAt = expiresAt;
    }

    AgentState copy() {
        return new AgentState(conversationId, currentResultSetId,
                new ArrayList<>(recentResultSets), selectedCandidate,
                pendingAction, lastActionResult, expiresAt);
    }

    public String conversationId() { return conversationId; }
    public String currentResultSetId() { return currentResultSetId; }
    public List<CandidateResultSet> recentResultSets() { return List.copyOf(recentResultSets); }
    public Candidate selectedCandidate() { return selectedCandidate; }
    public PendingActionView pendingAction() { return pendingAction; }
    public PatientActionResponse lastActionResult() { return lastActionResult; }
    public Instant expiresAt() { return expiresAt; }

    void currentResultSetId(String value) { currentResultSetId = value; }
    List<CandidateResultSet> mutableResultSets() { return recentResultSets; }
    void selectedCandidate(Candidate value) { selectedCandidate = value; }
    void pendingAction(PendingActionView value) { pendingAction = value; }
    void lastActionResult(PatientActionResponse value) { lastActionResult = value; }
    void expiresAt(Instant value) { expiresAt = value; }
}
