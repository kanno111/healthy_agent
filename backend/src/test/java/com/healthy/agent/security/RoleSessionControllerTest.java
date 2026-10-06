package com.healthy.agent.security;

import com.healthy.agent.common.GlobalExceptionHandler;
import com.healthy.agent.config.WebMvcConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({PatientSessionController.class, AdminSessionController.class})
@Import({GatewayIdentityInterceptor.class, WebMvcConfig.class, GlobalExceptionHandler.class})
class RoleSessionControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsCurrentPatient() throws Exception {
        mockMvc.perform(get("/api/agent/patient/auth/me")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "8")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "PATIENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(8))
                .andExpect(jsonPath("$.data.role").value("PATIENT"))
                .andExpect(jsonPath("$.data.authenticated").value(true));
    }

    @Test
    void returnsCurrentAdmin() throws Exception {
        mockMvc.perform(get("/api/agent/admin/auth/me")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "2")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "STAFF"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(2))
                .andExpect(jsonPath("$.data.role").value("STAFF"))
                .andExpect(jsonPath("$.data.authenticated").value(true));
    }

    @Test
    void rejectsRequestWithoutGatewayIdentity() throws Exception {
        mockMvc.perform(get("/api/agent/patient/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void preventsAdminFromUsingPatientEndpoint() throws Exception {
        mockMvc.perform(get("/api/agent/patient/auth/me")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "2")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "STAFF"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void preventsPatientFromUsingAdminEndpoint() throws Exception {
        mockMvc.perform(get("/api/agent/admin/auth/me")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "8")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "PATIENT"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }
}
