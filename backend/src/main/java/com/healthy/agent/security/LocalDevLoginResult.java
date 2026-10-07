package com.healthy.agent.security;

public record LocalDevLoginResult(
        String token,
        long userId,
        String name,
        String role,
        String mode
) {
}
