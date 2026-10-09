package com.healthy.agent.chat;

import com.healthy.agent.common.ApiResponse;
import com.healthy.agent.security.GatewayIdentityInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/patient/chat")
public class PatientChatController {
    private final PatientChatService chatService;

    public PatientChatController(PatientChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/messages")
    public ApiResponse<PatientChatResponse> send(
            @RequestBody PatientChatRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest servletRequest
    ) {
        String message = request == null ? null : request.message();
        String conversationId = request == null ? null : request.conversationId();
        long userId = (long) servletRequest.getAttribute(GatewayIdentityInterceptor.CURRENT_USER_ID);
        return ApiResponse.success(chatService.answer(
                message, authorization, userId, conversationId));
    }
}
