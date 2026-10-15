package com.healthy.agent.chat.history;

import com.healthy.agent.action.PatientActionService;
import com.healthy.agent.common.ApiResponse;
import com.healthy.agent.security.GatewayIdentityInterceptor;
import com.healthy.agent.state.AgentStateService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agent/patient/conversations")
public class PatientConversationController {
    private final PatientConversationHistoryService historyService;
    private final AgentStateService stateService;
    private final PatientActionService actionService;

    public PatientConversationController(
            PatientConversationHistoryService historyService,
            AgentStateService stateService,
            PatientActionService actionService
    ) {
        this.historyService = historyService;
        this.stateService = stateService;
        this.actionService = actionService;
    }

    @PostMapping
    public ApiResponse<ConversationSummary> create(HttpServletRequest request) {
        return ApiResponse.success(historyService.create(userId(request)));
    }

    @GetMapping
    public ApiResponse<List<ConversationSummary>> list(HttpServletRequest request) {
        return ApiResponse.success(historyService.list(userId(request)));
    }

    @PatchMapping("/{conversationId}")
    public ApiResponse<ConversationSummary> rename(
            @PathVariable String conversationId,
            @RequestBody(required = false) RenameConversationRequest renameRequest,
            HttpServletRequest request
    ) {
        return ApiResponse.success(historyService.rename(
                userId(request), conversationId,
                renameRequest == null ? null : renameRequest.title()));
    }

    @GetMapping("/{conversationId}/messages")
    public ApiResponse<ConversationMessagePage> messages(
            @PathVariable String conversationId,
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request
    ) {
        return ApiResponse.success(historyService.messages(
                userId(request), conversationId, before, size));
    }

    @DeleteMapping("/{conversationId}")
    public ApiResponse<Void> delete(
            @PathVariable String conversationId,
            HttpServletRequest request
    ) {
        long userId = userId(request);
        String scoped = historyService.requireOwned(userId, conversationId);
        actionService.discardConversation(userId, scoped);
        historyService.delete(userId, conversationId);
        stateService.clearConversation(scoped);
        return ApiResponse.success(null);
    }

    private long userId(HttpServletRequest request) {
        return (long) request.getAttribute(GatewayIdentityInterceptor.CURRENT_USER_ID);
    }
}
