package com.healthy.agent.security;

public record AgentSession(long userId, String role, boolean authenticated) {
    public static AgentSession authenticated(long userId, String role) {
        return new AgentSession(userId, role, true);
    }
}
