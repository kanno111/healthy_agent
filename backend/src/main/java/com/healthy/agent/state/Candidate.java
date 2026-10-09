package com.healthy.agent.state;

/** A compact, trusted reference to one business object returned by Healthy. */
public record Candidate(
        long businessId,
        CandidateType type,
        String displayText,
        Long doctorId,
        String doctorName,
        Long departmentId,
        String departmentName,
        String date,
        String sessionType,
        String sessionName,
        String status,
        Integer remainingCapacity
) {
    public Candidate {
        if (businessId < 1) throw new IllegalArgumentException("businessId must be positive");
        if (type == null) throw new IllegalArgumentException("type is required");
        displayText = displayText == null || displayText.isBlank()
                ? type.name() : displayText.strip();
    }
}
