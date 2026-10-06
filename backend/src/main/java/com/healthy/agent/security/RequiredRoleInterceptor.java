package com.healthy.agent.security;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public class RequiredRoleInterceptor implements HandlerInterceptor {
    public static final String PATIENT_ROLE = "PATIENT";
    public static final String ADMIN_ROLE = "STAFF";

    private final String requiredRole;

    public RequiredRoleInterceptor(String requiredRole) {
        this.requiredRole = requiredRole;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object currentRole = request.getAttribute(GatewayIdentityInterceptor.CURRENT_USER_ROLE);
        if (currentRole == null) {
            throw new AgentException(AgentErrorCode.UNAUTHORIZED);
        }
        if (!requiredRole.equals(currentRole.toString())) {
            throw new AgentException(AgentErrorCode.FORBIDDEN);
        }
        return true;
    }
}
