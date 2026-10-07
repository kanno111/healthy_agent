package com.healthy.agent.knowledge;

import com.healthy.agent.common.ApiResponse;
import com.healthy.agent.knowledge.index.KnowledgeIndexResult;
import com.healthy.agent.knowledge.index.KnowledgeIndexService;
import com.healthy.agent.security.GatewayIdentityInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/agent/admin/knowledge/documents")
public class KnowledgeDocumentController {
    private final KnowledgeDocumentService documentService;
    private final KnowledgeIndexService indexService;

    public KnowledgeDocumentController(
            KnowledgeDocumentService documentService,
            KnowledgeIndexService indexService
    ) {
        this.documentService = documentService;
        this.indexService = indexService;
    }

    @GetMapping
    public ApiResponse<List<KnowledgeDocument>> list() {
        return ApiResponse.success(documentService.list());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<KnowledgeDocument> upload(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request
    ) {
        long uploaderId = (Long) request.getAttribute(GatewayIdentityInterceptor.CURRENT_USER_ID);
        return ApiResponse.success(documentService.upload(file, uploaderId));
    }

    @PostMapping("/{documentId}/parse-preview")
    public ApiResponse<KnowledgeDocumentPreview> preview(@PathVariable String documentId) {
        return ApiResponse.success(documentService.preview(documentId));
    }

    @PostMapping("/{documentId}/index")
    public ApiResponse<KnowledgeIndexResult> buildIndex(
            @PathVariable String documentId,
            @RequestParam(defaultValue = "false") boolean force
    ) {
        return ApiResponse.success(indexService.build(documentId, force));
    }

    @DeleteMapping("/{documentId}")
    public ApiResponse<Void> delete(@PathVariable String documentId) {
        documentService.delete(documentId);
        return ApiResponse.success(null);
    }
}
