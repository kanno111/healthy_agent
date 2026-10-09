package com.healthy.agent.state;

public record CandidateSelector(
        String resultSetId,
        Integer position,
        CandidateType type,
        String doctorName,
        String departmentName,
        String date,
        String sessionName,
        String status
) {
}
