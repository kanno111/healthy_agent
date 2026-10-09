package com.healthy.agent.action;

import com.healthy.agent.common.GlobalExceptionHandler;
import com.healthy.agent.config.WebMvcConfig;
import com.healthy.agent.security.GatewayIdentityInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PatientActionController.class)
@Import({GatewayIdentityInterceptor.class, WebMvcConfig.class, GlobalExceptionHandler.class})
class PatientActionControllerTest {
    private static final String RAW_CONVERSATION = "11111111-1111-4111-8111-111111111111";
    private static final String SCOPED_CONVERSATION = "patient:8:" + RAW_CONVERSATION;
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PatientActionService actionService;

    @Test
    void patientCanConfirmPreparedAction() throws Exception {
        when(actionService.confirm(
                "action-1", SCOPED_CONVERSATION, 8L, "Bearer cloud-token"))
                .thenReturn(new PatientActionResponse(
                        "action-1", PatientActionType.CANCEL_APPOINTMENT,
                        PatientActionStatus.SUCCEEDED, "预约已成功取消",
                        new PatientActionPreview(
                                "确认取消预约", "请核对预约信息。",
                                "确认取消", "暂不取消",
                                List.of(new ActionPreviewField(
                                        "appointmentNo", "预约号", "A201")))));

                mockMvc.perform(post("/api/agent/patient/actions/action-1/confirm")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "8")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "PATIENT")
                        .header("Authorization", "Bearer cloud-token")
                        .contentType("application/json")
                        .content("{\"conversationId\":\"" + RAW_CONVERSATION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.message").value("预约已成功取消"))
                .andExpect(jsonPath("$.data.preview.title").value("确认取消预约"))
                .andExpect(jsonPath("$.data.preview.fields[0].key").value("appointmentNo"));

        verify(actionService).confirm(
                "action-1", SCOPED_CONVERSATION, 8L, "Bearer cloud-token");
    }

    @Test
    void staffCannotConfirmPatientAction() throws Exception {
        mockMvc.perform(post("/api/agent/patient/actions/action-1/confirm")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "12")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "STAFF"))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingConversationBodyReturnsClearClientErrorInsteadOfInternalError() throws Exception {
        mockMvc.perform(post("/api/agent/patient/actions/action-1/confirm")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "8")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "PATIENT")
                        .header("Authorization", "Bearer cloud-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40018))
                .andExpect(jsonPath("$.message").value("会话标识无效，请新建对话后重试"));
    }
}
