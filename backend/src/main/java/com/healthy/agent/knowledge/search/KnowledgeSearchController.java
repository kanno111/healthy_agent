package com.healthy.agent.knowledge.search;

import com.healthy.agent.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/admin/knowledge/search")
public class KnowledgeSearchController {
    private final KnowledgeSearchService searchService;

    public KnowledgeSearchController(KnowledgeSearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping
    public ApiResponse<KnowledgeSearchResponse> search(@RequestBody KnowledgeSearchRequest request) {
        return ApiResponse.success(searchService.search(request));
    }
}
