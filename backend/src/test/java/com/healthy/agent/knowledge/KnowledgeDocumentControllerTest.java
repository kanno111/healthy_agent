package com.healthy.agent.knowledge;

import com.healthy.agent.common.GlobalExceptionHandler;
import com.healthy.agent.config.WebMvcConfig;
import com.healthy.agent.knowledge.index.KnowledgeIndexResult;
import com.healthy.agent.knowledge.index.KnowledgeIndexService;
import com.healthy.agent.security.GatewayIdentityInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(KnowledgeDocumentController.class)
@Import({GatewayIdentityInterceptor.class, WebMvcConfig.class, GlobalExceptionHandler.class})
class KnowledgeDocumentControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KnowledgeDocumentService documentService;

    @MockitoBean
    private KnowledgeIndexService indexService;

    @Test
    void staffCanUploadDocument() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "制度.md", "text/markdown", "# 制度".getBytes(StandardCharsets.UTF_8)
        );
        when(documentService.upload(any(), eq(12L))).thenReturn(document());

        mockMvc.perform(multipart("/api/agent/admin/knowledge/documents")
                        .file(file)
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "12")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "STAFF"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("document-id"))
                .andExpect(jsonPath("$.data.fileName").value("制度.md"))
                .andExpect(jsonPath("$.data.status").value("UPLOADED"));
    }

    @Test
    void staffCanListDocuments() throws Exception {
        when(documentService.list()).thenReturn(List.of(document()));

        mockMvc.perform(get("/api/agent/admin/knowledge/documents")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "12")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "STAFF"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fileName").value("制度.md"));
    }

    @Test
    void patientCannotUploadDocument() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "制度.md", MediaType.TEXT_MARKDOWN_VALUE, "# 制度".getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/agent/admin/knowledge/documents")
                        .file(file)
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "8")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "PATIENT"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void anonymousUserCannotListDocuments() throws Exception {
        mockMvc.perform(get("/api/agent/admin/knowledge/documents"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void staffCanPreviewDocument() throws Exception {
        when(documentService.preview("document-id")).thenReturn(new KnowledgeDocumentPreview(
                "document-id", "制度.md", "text/markdown; charset=utf-8",
                "就诊制度内容", 6, 6, false
        ));

        mockMvc.perform(post("/api/agent/admin/knowledge/documents/document-id/parse-preview")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "12")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "STAFF"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentId").value("document-id"))
                .andExpect(jsonPath("$.data.preview").value("就诊制度内容"))
                .andExpect(jsonPath("$.data.truncated").value(false));
    }

    @Test
    void staffCanDeleteDocument() throws Exception {
        mockMvc.perform(delete("/api/agent/admin/knowledge/documents/document-id")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "12")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "STAFF"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void staffCanBuildDocumentIndex() throws Exception {
        Instant indexedAt = Instant.parse("2026-10-07T02:00:00Z");
        when(indexService.build("document-id", false)).thenReturn(new KnowledgeIndexResult(
                "document-id", true, 3, indexedAt, false
        ));

        mockMvc.perform(post("/api/agent/admin/knowledge/documents/document-id/index")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "12")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "STAFF"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.indexed").value(true))
                .andExpect(jsonPath("$.data.chunkCount").value(3))
                .andExpect(jsonPath("$.data.skipped").value(false));
    }

    @Test
    void patientCannotBuildDocumentIndex() throws Exception {
        mockMvc.perform(post("/api/agent/admin/knowledge/documents/document-id/index")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "8")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "PATIENT"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void patientCannotDeleteDocument() throws Exception {
        mockMvc.perform(delete("/api/agent/admin/knowledge/documents/document-id")
                        .header(GatewayIdentityInterceptor.USER_ID_HEADER, "8")
                        .header(GatewayIdentityInterceptor.ROLE_HEADER, "PATIENT"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    private KnowledgeDocument document() {
        return new KnowledgeDocument(
                "document-id",
                "制度.md",
                8,
                "text/markdown; charset=utf-8",
                "UPLOADED",
                false,
                0,
                null,
                Instant.parse("2026-10-06T12:00:00Z")
        );
    }
}
