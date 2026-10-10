package com.healthy.agent.knowledge.evaluation;

import com.healthy.agent.common.GlobalExceptionHandler;
import com.healthy.agent.config.WebMvcConfig;
import com.healthy.agent.knowledge.search.KnowledgeSearchStrategy;
import com.healthy.agent.security.GatewayIdentityInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(KnowledgeRetrievalEvaluationController.class)
@Import({GatewayIdentityInterceptor.class, WebMvcConfig.class, GlobalExceptionHandler.class})
class KnowledgeRetrievalEvaluationControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KnowledgeRetrievalEvaluationService evaluationService;

    @Test
    void staffCanRunRetrievalEvaluation() throws Exception {
        when(evaluationService.evaluate(any())).thenReturn(new KnowledgeRetrievalEvaluationResponse(
                "v3", KnowledgeSearchStrategy.BM25, 5,
                104, 96, 8, 0.98d, 0.25d, 0.99d, 0.96d, 0.96d, 0.5d,
                Instant.parse("2026-10-09T02:00:00Z"), List.of()));

        mockMvc.perform(post("/api/agent/admin/knowledge/evaluations/retrieval")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "12")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "STAFF")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strategy\":\"BM25\",\"limit\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.datasetVersion").value("v3"))
                .andExpect(jsonPath("$.data.strategy").value("BM25"))
                .andExpect(jsonPath("$.data.totalCases").value(104))
                .andExpect(jsonPath("$.data.hitRateAtK").value(0.99));
    }

    @Test
    void patientCannotRunRetrievalEvaluation() throws Exception {
        mockMvc.perform(post("/api/agent/admin/knowledge/evaluations/retrieval")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "8")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "PATIENT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strategy\":\"BM25\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }
}
