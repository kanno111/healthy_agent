package com.healthy.agent.memory.profile;

import com.healthy.agent.common.ApiResponse;
import com.healthy.agent.security.GatewayIdentityInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/patient/profile")
public class PatientProfileController {
    private final PatientProfileService profileService;

    public PatientProfileController(PatientProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public ApiResponse<PatientProfile> get(HttpServletRequest request) {
        return ApiResponse.success(profileService.get(userId(request)));
    }

    @PutMapping
    public ApiResponse<PatientProfile> replace(
            @RequestBody(required = false) PatientProfileUpdateRequest update,
            HttpServletRequest request
    ) {
        return ApiResponse.success(profileService.replace(userId(request), update));
    }

    @DeleteMapping
    public ApiResponse<Void> delete(HttpServletRequest request) {
        profileService.delete(userId(request));
        return ApiResponse.success(null);
    }

    private long userId(HttpServletRequest request) {
        return (long) request.getAttribute(GatewayIdentityInterceptor.CURRENT_USER_ID);
    }
}
