package com.healthy.agent.knowledge.rag;

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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(KnowledgeRagController.class)
@Import({GatewayIdentityInterceptor.class, WebMvcConfig.class, GlobalExceptionHandler.class})
class KnowledgeRagControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KnowledgeRagService ragService;

    @Test
    void staffCanAskKnowledgeBase() throws Exception {
        when(ragService.answer(any())).thenReturn(new KnowledgeRagResponse(
                "如何取消预约？",
                "可在就诊日前取消。[资料1]",
                "deepseek-flash",
                "BAAI/bge-m3",
                List.of(new KnowledgeRagCitation(
                        1, "doc-1-0", "doc-1", "预约制度.md", 0,
                        "就诊日前可以取消预约。", 0.91)),
                new TokenUsage(120, 18, 138)
        ));

        mockMvc.perform(post("/api/agent/admin/knowledge/rag/ask")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "12")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "STAFF")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"如何取消预约？\",\"limit\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.model").value("deepseek-flash"))
                .andExpect(jsonPath("$.data.answer").value("可在就诊日前取消。[资料1]"))
                .andExpect(jsonPath("$.data.citations[0].fileName").value("预约制度.md"))
                .andExpect(jsonPath("$.data.usage.totalTokens").value(138));
    }

    @Test
    void patientCannotAskAdminRag() throws Exception {
        mockMvc.perform(post("/api/agent/admin/knowledge/rag/ask")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "8")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "PATIENT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"预约规则\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }
}
