package com.healthy.agent.chat;

import com.healthy.agent.common.GlobalExceptionHandler;
import com.healthy.agent.config.WebMvcConfig;
import com.healthy.agent.llm.TokenUsage;
import com.healthy.agent.security.GatewayIdentityInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PatientChatController.class)
@Import({GatewayIdentityInterceptor.class, WebMvcConfig.class, GlobalExceptionHandler.class})
class PatientChatControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PatientChatService chatService;

    @Test
    void patientCanSendMessageWithAuthorizationForToolCalling() throws Exception {
        String conversationId = "11111111-1111-4111-8111-111111111111";
        when(chatService.answer("查询我的预约", "Bearer cloud-token", 8L, conversationId))
                .thenReturn(new PatientChatResponse(
                "查询我的预约", "你目前没有预约记录。", "TOOL",
                "deepseek-flash", null, List.of(),
                new TokenUsage(100, 20, 120), List.of("list_my_appointments")
        ));

        mockMvc.perform(post("/api/agent/patient/chat/messages")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "8")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "PATIENT")
                        .header("Authorization", "Bearer cloud-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + conversationId
                                + "\",\"message\":\"查询我的预约\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value("你目前没有预约记录。"))
                .andExpect(jsonPath("$.data.mode").value("TOOL"))
                .andExpect(jsonPath("$.data.tools[0]").value("list_my_appointments"))
                .andExpect(jsonPath("$.data.usage.totalTokens").value(120));

        verify(chatService).answer(
                "查询我的预约", "Bearer cloud-token", 8L, conversationId);
    }

    @Test
    void staffCannotUsePatientChat() throws Exception {
        mockMvc.perform(post("/api/agent/patient/chat/messages")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "12")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "STAFF")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"11111111-1111-4111-8111-111111111111\","
                                + "\"message\":\"预约规则\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }
}
