package com.healthy.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.security")
public record AgentSecurityProperties(
        String mode,
        long localPatientId,
        String localPatientName,
        long localAdminId,
        String localAdminName
) {
    public static final String GATEWAY_MODE = "gateway";
    public static final String LOCAL_DEV_MODE = "local-dev";

    public AgentSecurityProperties {
        mode = mode == null || mode.isBlank() ? GATEWAY_MODE : mode.strip().toLowerCase();
        localPatientName = defaultName(localPatientName, "本地测试患者");
        localAdminName = defaultName(localAdminName, "本地测试管理员");
        if (localPatientId < 1) {
            localPatientId = 900001L;
        }
        if (localAdminId < 1) {
            localAdminId = 900002L;
        }
    }

    public boolean localDevEnabled() {
        return LOCAL_DEV_MODE.equals(mode);
    }

    private static String defaultName(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
