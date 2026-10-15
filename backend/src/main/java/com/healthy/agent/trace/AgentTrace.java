package com.healthy.agent.trace;

import java.time.Instant;
import java.util.List;

public record AgentTrace(
        String turnId,
        Instant startedAt,
        long totalDurationMs,
        List<AgentTraceStep> steps
) {
    public AgentTrace {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
