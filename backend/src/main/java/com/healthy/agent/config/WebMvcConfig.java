package com.healthy.agent.config;

import com.healthy.agent.security.GatewayIdentityInterceptor;
import com.healthy.agent.security.RequiredRoleInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final GatewayIdentityInterceptor gatewayIdentityInterceptor;

    public WebMvcConfig(GatewayIdentityInterceptor gatewayIdentityInterceptor) {
        this.gatewayIdentityInterceptor = gatewayIdentityInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(gatewayIdentityInterceptor)
                .addPathPatterns("/api/agent/**")
                .excludePathPatterns("/api/agent/dev-auth/**");
        registry.addInterceptor(new RequiredRoleInterceptor(RequiredRoleInterceptor.PATIENT_ROLE))
                .addPathPatterns("/api/agent/patient/**");
        registry.addInterceptor(new RequiredRoleInterceptor(RequiredRoleInterceptor.ADMIN_ROLE))
                .addPathPatterns("/api/agent/admin/**");
    }
}
