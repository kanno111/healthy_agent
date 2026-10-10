package com.healthy.agent.chat;

import com.healthy.agent.common.ApiResponse;
import com.healthy.agent.security.GatewayIdentityInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;

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

    @PostMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<PatientChatStreamEvent> stream(
            @RequestBody PatientChatRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        servletResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
        servletResponse.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        servletResponse.setHeader("X-Accel-Buffering", "no");
        String message = request == null ? null : request.message();
        String conversationId = request == null ? null : request.conversationId();
        long userId = (long) servletRequest.getAttribute(GatewayIdentityInterceptor.CURRENT_USER_ID);
        return chatService.stream(message, authorization, userId, conversationId);
    }
}
