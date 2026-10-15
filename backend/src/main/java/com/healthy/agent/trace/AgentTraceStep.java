package com.healthy.agent.trace;

import java.time.Instant;
import java.util.Map;

public record AgentTraceStep(
        int sequence,
        AgentTraceStepType type,
        String name,
        Instant startedAt,
        long durationMs,
        AgentTraceStatus status,
        String summary,
        Map<String, Object> details
) {
    public AgentTraceStep {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
