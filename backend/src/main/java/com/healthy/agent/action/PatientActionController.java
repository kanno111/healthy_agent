package com.healthy.agent.action;

import com.healthy.agent.common.ApiResponse;
import com.healthy.agent.chat.history.PatientConversationHistoryService;
import com.healthy.agent.security.GatewayIdentityInterceptor;
import com.healthy.agent.state.PatientConversationIds;
import com.healthy.agent.trace.AgentTraceRecorder;
import com.healthy.agent.trace.AgentTraceStepType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.function.Supplier;
import java.util.function.Consumer;

@RestController
@RequestMapping("/api/agent/patient/actions")
public class PatientActionController {
    private final PatientActionService actionService;
    private final PatientConversationHistoryService historyService;

    public PatientActionController(
            PatientActionService actionService,
            PatientConversationHistoryService historyService
    ) {
        this.actionService = actionService;
        this.historyService = historyService;
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
        String rawConversationId = PatientConversationIds.normalize(
                decision == null ? null : decision.conversationId());
        String conversationId = historyService.requireOwned(userId, rawConversationId);
        return tracedAction("确认并执行写操作", () -> actionService.confirm(
                actionId, conversationId, userId, authorization),
                response -> historyService.updateAction(userId, rawConversationId, response));
    }

    @PostMapping("/{actionId}/reject")
    public ApiResponse<PatientActionResponse> reject(
            @PathVariable String actionId,
            @RequestBody(required = false) PatientActionDecisionRequest decision,
            HttpServletRequest request
    ) {
        long userId = (long) request.getAttribute(
                GatewayIdentityInterceptor.CURRENT_USER_ID);
        String rawConversationId = PatientConversationIds.normalize(
                decision == null ? null : decision.conversationId());
        String conversationId = historyService.requireOwned(userId, rawConversationId);
        return tracedAction("拒绝待确认操作", () -> actionService.reject(
                actionId, conversationId, userId),
                response -> historyService.updateAction(userId, rawConversationId, response));
    }

    private ApiResponse<PatientActionResponse> tracedAction(
            String stepName,
            Supplier<PatientActionResponse> operation,
            Consumer<PatientActionResponse> afterSuccess
    ) {
        AgentTraceRecorder trace = AgentTraceRecorder.create();
        AgentTraceRecorder.StepHandle step = trace.start(AgentTraceStepType.HITL, stepName);
        try {
            PatientActionResponse response = operation.get();
            Map<String, Object> details = Map.of(
                    "actionType", response.type().name(),
                    "actionStatus", response.status().name());
            if (response.status() == PatientActionStatus.FAILED) {
                step.failure("写操作处理失败", details);
            } else {
                step.success("写操作状态：" + response.status().name(), details);
            }
            afterSuccess.accept(response);
            trace.complete();
            return ApiResponse.success(response.withTrace(trace.snapshot()));
        } catch (RuntimeException exception) {
            step.failure("写操作处理异常", Map.of(
                    "errorType", exception.getClass().getSimpleName()));
            trace.complete();
            throw exception;
        }
    }
}
