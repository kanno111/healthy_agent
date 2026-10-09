package com.healthy.agent.action;

import com.healthy.agent.common.ApiResponse;
import com.healthy.agent.security.GatewayIdentityInterceptor;
import com.healthy.agent.state.PatientConversationIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/patient/actions")
public class PatientActionController {
    private final PatientActionService actionService;

    public PatientActionController(PatientActionService actionService) {
        this.actionService = actionService;
    }

    @PostMapping("/{actionId}/confirm")
    public ApiResponse<PatientActionResponse> confirm(
            @PathVariable String actionId,
            @RequestBody(required = false) PatientActionDecisionRequest decision,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request
    ) {
        long userId = (long) request.getAttribute(
                GatewayIdentityInterceptor.CURRENT_USER_ID);
        String conversationId = PatientConversationIds.scoped(
                userId, decision == null ? null : decision.conversationId());
        return ApiResponse.success(actionService.confirm(
                actionId, conversationId, userId, authorization));
    }

    @PostMapping("/{actionId}/reject")
    public ApiResponse<PatientActionResponse> reject(
            @PathVariable String actionId,
            @RequestBody(required = false) PatientActionDecisionRequest decision,
            HttpServletRequest request
    ) {
        long userId = (long) request.getAttribute(
                GatewayIdentityInterceptor.CURRENT_USER_ID);
        String conversationId = PatientConversationIds.scoped(
                userId, decision == null ? null : decision.conversationId());
        return ApiResponse.success(actionService.reject(
                actionId, conversationId, userId));
    }
}
