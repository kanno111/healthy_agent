package com.healthy.agent.state;

import java.time.Instant;
import java.util.List;

public record CandidateResultSet(
        String resultSetId,
        String description,
        CandidateType type,
        List<Candidate> candidates,
        Instant createdAt
) {
    public CandidateResultSet {
        if (resultSetId == null || resultSetId.isBlank()) {
            throw new IllegalArgumentException("resultSetId is required");
        }
        description = description == null || description.isBlank()
                ? "最近查询结果" : description.strip();
        if (type == null) throw new IllegalArgumentException("type is required");
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
    }
}
