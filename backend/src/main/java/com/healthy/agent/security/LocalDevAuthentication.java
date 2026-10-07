package com.healthy.agent.security;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.config.AgentSecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@Component
public class LocalDevAuthentication {
    private static final String BEARER_PREFIX = "Bearer ";

    private final AgentSecurityProperties properties;
    private final String patientToken;
    private final String adminToken;

    public LocalDevAuthentication(AgentSecurityProperties properties) {
        this.properties = properties;
        this.patientToken = newToken();
        this.adminToken = newToken();
    }

    public boolean enabled() {
        return properties.localDevEnabled();
    }

    public LocalDevLoginResult login(String requestedRole) {
        requireEnabled();
        String role = requestedRole == null ? "" : requestedRole.strip().toUpperCase();
        return switch (role) {
            case RequiredRoleInterceptor.PATIENT_ROLE -> new LocalDevLoginResult(
                    patientToken, properties.localPatientId(), properties.localPatientName(),
                    RequiredRoleInterceptor.PATIENT_ROLE, AgentSecurityProperties.LOCAL_DEV_MODE);
            case RequiredRoleInterceptor.ADMIN_ROLE -> new LocalDevLoginResult(
                    adminToken, properties.localAdminId(), properties.localAdminName(),
                    RequiredRoleInterceptor.ADMIN_ROLE, AgentSecurityProperties.LOCAL_DEV_MODE);
            default -> throw new AgentException(AgentErrorCode.INVALID_LOCAL_DEV_ROLE);
        };
    }

    public LocalDevIdentity authenticate(HttpServletRequest request) {
        if (!enabled()) {
            return null;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).strip();
        if (secureEquals(token, adminToken)) {
            return new LocalDevIdentity(properties.localAdminId(), RequiredRoleInterceptor.ADMIN_ROLE);
        }
        if (secureEquals(token, patientToken)) {
            return new LocalDevIdentity(properties.localPatientId(), RequiredRoleInterceptor.PATIENT_ROLE);
        }
        return null;
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new AgentException(AgentErrorCode.LOCAL_DEV_AUTH_DISABLED);
        }
    }

    private boolean secureEquals(String actual, String expected) {
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private String newToken() {
        return "local-dev." + UUID.randomUUID();
    }

    public record LocalDevIdentity(long userId, String role) {
    }
}
