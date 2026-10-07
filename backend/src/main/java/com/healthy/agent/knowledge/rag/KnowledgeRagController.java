package com.healthy.agent.knowledge.rag;

import com.healthy.agent.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/admin/knowledge/rag")
public class KnowledgeRagController {
    private final KnowledgeRagService ragService;

    public KnowledgeRagController(KnowledgeRagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/ask")
    public ApiResponse<KnowledgeRagResponse> ask(@RequestBody KnowledgeRagRequest request) {
        return ApiResponse.success(ragService.answer(request));
    }
}
