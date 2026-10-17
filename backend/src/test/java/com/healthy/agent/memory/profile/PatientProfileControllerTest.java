package com.healthy.agent.memory.profile;

import com.healthy.agent.common.GlobalExceptionHandler;
import com.healthy.agent.config.WebMvcConfig;
import com.healthy.agent.security.GatewayIdentityInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PatientProfileController.class)
@Import({GatewayIdentityInterceptor.class, WebMvcConfig.class, GlobalExceptionHandler.class})
class PatientProfileControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PatientProfileService profileService;

    @Test
    void getsUpdatesAndDeletesOnlyCurrentPatientsProfile() throws Exception {
        PatientProfile profile = new PatientProfile(
                AppointmentPeriod.MORNING,
                ResponseStyle.CONCISE,
                32,
                Sex.MALE,
                175,
                new BigDecimal("70.5"),
                List.of("高血压"),
                List.of("青霉素"),
                List.of("二甲双胍 500mg"),
                SmokingStatus.FORMER,
                AlcoholStatus.NEVER,
                Instant.parse("2026-10-15T06:00:00Z"));
        PatientProfileUpdateRequest update = new PatientProfileUpdateRequest(
                AppointmentPeriod.MORNING, ResponseStyle.CONCISE, 32, Sex.MALE,
                175, new BigDecimal("70.5"), List.of("高血压"), List.of("青霉素"),
                List.of("二甲双胍 500mg"), SmokingStatus.FORMER, AlcoholStatus.NEVER);
        when(profileService.get(8L)).thenReturn(profile);
        when(profileService.replace(8L, update)).thenReturn(profile);

        mockMvc.perform(get("/api/agent/patient/profile")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "8")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "PATIENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appointmentPeriod").value("MORNING"))
                .andExpect(jsonPath("$.data.responseStyle").value("CONCISE"))
                .andExpect(jsonPath("$.data.conditions[0]").value("高血压"))
                .andExpect(jsonPath("$.data.allergies[0]").value("青霉素"));

        mockMvc.perform(put("/api/agent/patient/profile")
                        .contentType("application/json")
                        .content("""
                                {"appointmentPeriod":"MORNING","responseStyle":"CONCISE",
                                 "age":32,"sex":"MALE","heightCm":175,"weightKg":70.5,
                                 "conditions":["高血压"],"allergies":["青霉素"],
                                 "medications":["二甲双胍 500mg"],
                                 "smokingStatus":"FORMER","alcoholStatus":"NEVER"}
                                """)
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "8")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "PATIENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedAt").value("2026-10-15T06:00:00Z"));

        mockMvc.perform(delete("/api/agent/patient/profile")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "8")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "PATIENT"))
                .andExpect(status().isOk());

        verify(profileService).get(8L);
        verify(profileService).replace(8L, update);
        verify(profileService).delete(8L);
    }

    @Test
    void rejectsAdminIdentityAtPatientBoundary() throws Exception {
        mockMvc.perform(get("/api/agent/patient/profile")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "8")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "ADMIN"))
                .andExpect(status().isForbidden());
    }
}
