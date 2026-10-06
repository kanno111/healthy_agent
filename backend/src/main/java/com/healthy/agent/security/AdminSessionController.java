package com.healthy.agent.security;

import com.healthy.agent.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/admin/auth")
public class AdminSessionController {
    @GetMapping("/me")
    public ApiResponse<AgentSession> currentAdmin(HttpServletRequest request) {
        long adminUserId = (Long) request.getAttribute(GatewayIdentityInterceptor.CURRENT_USER_ID);
        return ApiResponse.success(AgentSession.authenticated(adminUserId, RequiredRoleInterceptor.ADMIN_ROLE));
    }
}
