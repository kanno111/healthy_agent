package com.healthy.agent.knowledge.search;

import com.healthy.agent.common.GlobalExceptionHandler;
import com.healthy.agent.config.WebMvcConfig;
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

@WebMvcTest(KnowledgeSearchController.class)
@Import({GatewayIdentityInterceptor.class, WebMvcConfig.class, GlobalExceptionHandler.class})
class KnowledgeSearchControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KnowledgeSearchService searchService;

    @Test
    void staffCanTestVectorSearch() throws Exception {
        when(searchService.search(any())).thenReturn(new KnowledgeSearchResponse(
                "如何取消预约", "BAAI/bge-m3", 5,
                List.of(new KnowledgeSearchHit(
                        1, "doc-1-0", "doc-1", "预约制度.md", "text/markdown",
                        0, "就诊日前可取消预约", 0.92d
                ))
        ));

        mockMvc.perform(post("/api/agent/admin/knowledge/search")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "12")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "STAFF")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"如何取消预约\",\"limit\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.strategy").value("VECTOR"))
                .andExpect(jsonPath("$.data.embeddingModel").value("BAAI/bge-m3"))
                .andExpect(jsonPath("$.data.results[0].fileName").value("预约制度.md"))
                .andExpect(jsonPath("$.data.results[0].score").value(0.92));
    }

    @Test
    void staffCanSelectBm25Search() throws Exception {
        when(searchService.search(any())).thenReturn(new KnowledgeSearchResponse(
                "电子票据", KnowledgeSearchStrategy.BM25, null, 3, List.of()
        ));

        mockMvc.perform(post("/api/agent/admin/knowledge/search")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "12")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "STAFF")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"电子票据\",\"limit\":3,\"strategy\":\"BM25\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.strategy").value("BM25"))
                .andExpect(jsonPath("$.data.embeddingModel").doesNotExist());
    }

    @Test
    void staffCanSelectHybridRrfSearch() throws Exception {
        when(searchService.search(any())).thenReturn(new KnowledgeSearchResponse(
                "预约退款", KnowledgeSearchStrategy.HYBRID, "BAAI/bge-m3", 5, List.of()
        ));

        mockMvc.perform(post("/api/agent/admin/knowledge/search")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "12")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "STAFF")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"预约退款\",\"limit\":5,\"strategy\":\"HYBRID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.strategy").value("HYBRID"))
                .andExpect(jsonPath("$.data.embeddingModel").value("BAAI/bge-m3"));
    }

    @Test
    void patientCannotTestVectorSearch() throws Exception {
        mockMvc.perform(post("/api/agent/admin/knowledge/search")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "8")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "PATIENT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"预约规则\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }
}
