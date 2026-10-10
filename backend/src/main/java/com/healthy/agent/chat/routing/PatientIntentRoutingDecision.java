package com.healthy.agent.chat.routing;

import com.healthy.agent.llm.TokenUsage;

import java.util.EnumSet;
import java.util.Set;

public record PatientIntentRoutingDecision(
        Set<PatientIntentRoute> routes,
        boolean uncertain,
        boolean fallbackToAll,
        String reason,
        TokenUsage usage
) {
    public PatientIntentRoutingDecision {
        routes = routes == null || routes.isEmpty()
                ? Set.of(PatientIntentRoute.ALL)
                : Set.copyOf(EnumSet.copyOf(routes));
        reason = reason == null ? "" : reason.strip();
        usage = usage == null ? TokenUsage.empty() : usage;
    }

    public static PatientIntentRoutingDecision routed(
            Set<PatientIntentRoute> routes,
            String reason,
            TokenUsage usage
    ) {
        return new PatientIntentRoutingDecision(routes, false, false, reason, usage);
    }

    public static PatientIntentRoutingDecision fallback(
            String reason,
            TokenUsage usage
    ) {
        return new PatientIntentRoutingDecision(
                Set.of(PatientIntentRoute.ALL), true, true, reason, usage);
    }
}
