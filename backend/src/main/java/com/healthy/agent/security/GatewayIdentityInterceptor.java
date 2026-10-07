package com.healthy.agent.security;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class GatewayIdentityInterceptor implements HandlerInterceptor {
    public static final String USER_ID_HEADER = "X-Auth-User-Id";
    public static final String ROLE_HEADER = "X-Auth-Role";
    public static final String CURRENT_USER_ID = GatewayIdentityInterceptor.class.getName() + ".userId";
    public static final String CURRENT_USER_ROLE = GatewayIdentityInterceptor.class.getName() + ".role";

    private final LocalDevAuthentication localDevAuthentication;

    public GatewayIdentityInterceptor() {
        this.localDevAuthentication = null;
    }

    @Autowired
    public GatewayIdentityInterceptor(ObjectProvider<LocalDevAuthentication> localDevAuthentication) {
        this.localDevAuthentication = localDevAuthentication.getIfAvailable();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userIdHeader = request.getHeader(USER_ID_HEADER);
        String roleHeader = request.getHeader(ROLE_HEADER);
        if (userIdHeader == null || roleHeader == null || roleHeader.isBlank()) {
            if (acceptLocalDevIdentity(request)) {
                return true;
            }
            throw new AgentException(AgentErrorCode.UNAUTHORIZED);
        }

        try {
            long userId = Long.parseLong(userIdHeader);
            if (userId < 1) {
                throw new AgentException(AgentErrorCode.UNAUTHORIZED);
            }
            request.setAttribute(CURRENT_USER_ID, userId);
            request.setAttribute(CURRENT_USER_ROLE, roleHeader.trim());
            return true;
        } catch (NumberFormatException exception) {
            throw new AgentException(AgentErrorCode.UNAUTHORIZED);
        }
    }

    private boolean acceptLocalDevIdentity(HttpServletRequest request) {
        if (localDevAuthentication == null) {
            return false;
        }
        LocalDevAuthentication.LocalDevIdentity identity = localDevAuthentication.authenticate(request);
        if (identity == null) {
            return false;
        }
        request.setAttribute(CURRENT_USER_ID, identity.userId());
        request.setAttribute(CURRENT_USER_ROLE, identity.role());
        return true;
    }
}
