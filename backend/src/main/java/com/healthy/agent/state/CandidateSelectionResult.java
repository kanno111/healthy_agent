package com.healthy.agent.state;

import java.util.List;

public record CandidateSelectionResult(
        Status status,
        Candidate selected,
        CandidateResultSet resultSet,
        List<Candidate> candidates,
        String message
) {
    public enum Status { SELECTED, NOT_FOUND, AMBIGUOUS }

    public CandidateSelectionResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
