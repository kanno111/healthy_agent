package com.healthy.agent.security;

import com.healthy.agent.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LocalDevAuthController.class)
@org.springframework.context.annotation.Import(GlobalExceptionHandler.class)
class LocalDevAuthControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LocalDevAuthentication authentication;

    @Test
    void issuesLocalAdminSessionWithoutExistingJwt() throws Exception {
        when(authentication.login("STAFF")).thenReturn(new LocalDevLoginResult(
                "local-dev.token", 900002L, "本地测试管理员", "STAFF", "local-dev"));

        mockMvc.perform(post("/api/agent/dev-auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"STAFF\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("local-dev.token"))
                .andExpect(jsonPath("$.data.userId").value(900002))
                .andExpect(jsonPath("$.data.role").value("STAFF"))
                .andExpect(jsonPath("$.data.mode").value("local-dev"));
    }
}
