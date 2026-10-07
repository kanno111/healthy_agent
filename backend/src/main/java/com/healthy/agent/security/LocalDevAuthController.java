package com.healthy.agent.security;

import com.healthy.agent.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/dev-auth")
public class LocalDevAuthController {
    private final LocalDevAuthentication authentication;

    public LocalDevAuthController(LocalDevAuthentication authentication) {
        this.authentication = authentication;
    }

    @PostMapping("/login")
    public ApiResponse<LocalDevLoginResult> login(@RequestBody LocalDevLoginRequest request) {
        return ApiResponse.success(authentication.login(request == null ? null : request.role()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        return ApiResponse.success(null);
    }
}
