package com.healthy.agent.knowledge.evaluation;

import com.healthy.agent.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/admin/knowledge/evaluations/retrieval")
public class KnowledgeRetrievalEvaluationController {
    private final KnowledgeRetrievalEvaluationService evaluationService;

    public KnowledgeRetrievalEvaluationController(
            KnowledgeRetrievalEvaluationService evaluationService
    ) {
        this.evaluationService = evaluationService;
    }

    @PostMapping
    public ApiResponse<KnowledgeRetrievalEvaluationResponse> evaluate(
            @RequestBody(required = false) KnowledgeRetrievalEvaluationRequest request
    ) {
        return ApiResponse.success(evaluationService.evaluate(request));
    }
}
