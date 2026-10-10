package com.healthy.agent.chat.routing;

import com.healthy.agent.action.PatientActionService;
import com.healthy.agent.tool.PatientSpringAiTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class PatientIntentToolSelector {
    private static final Set<String> QUERY_TOOLS = Set.of(
            "list_departments",
            "search_doctors",
            "get_doctor_detail",
            "list_schedule_slots",
            "list_doctors_schedule_slots",
            "list_my_appointments",
            "list_my_waitlists",
            PatientSpringAiTools.SELECT_CANDIDATE);
    private static final Set<String> PREPARE_TOOLS = Set.of(
            PatientActionService.PREPARE_CREATE_APPOINTMENT,
            PatientActionService.PREPARE_CANCEL_APPOINTMENT,
            PatientActionService.PREPARE_JOIN_WAITLIST,
            PatientActionService.PREPARE_CANCEL_WAITLIST,
            PatientActionService.PREPARE_CONFIRM_WAITLIST);

    public List<ToolCallback> select(
            List<ToolCallback> safeCallbacks,
            PatientIntentRoutingDecision decision
    ) {
        if (safeCallbacks == null || safeCallbacks.isEmpty()) return List.of();
        if (decision == null) return List.copyOf(safeCallbacks);

        Set<PatientIntentRoute> routes = decision.routes();
        if (decision.fallbackToAll() || routes.contains(PatientIntentRoute.ALL)) {
            return List.copyOf(safeCallbacks);
        }
        if (routes.contains(PatientIntentRoute.CHAT) && routes.size() > 1) {
            return List.copyOf(safeCallbacks);
        }
        if (routes.equals(Set.of(PatientIntentRoute.CHAT))) return List.of();

        return safeCallbacks.stream()
                .filter(callback -> allowed(callback.getToolDefinition().name(), routes))
                .toList();
    }

    private boolean allowed(String toolName, Set<PatientIntentRoute> routes) {
        if (routes.contains(PatientIntentRoute.RAG)
                && PatientSpringAiTools.RAG_TOOL.equals(toolName)) return true;
        if (routes.contains(PatientIntentRoute.QUERY) && QUERY_TOOLS.contains(toolName)) return true;
        return routes.contains(PatientIntentRoute.WRITE)
                && (QUERY_TOOLS.contains(toolName) || PREPARE_TOOLS.contains(toolName));
    }
}
