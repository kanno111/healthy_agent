package com.healthy.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthy.agent.action.PendingActionView;
import com.healthy.agent.chat.PatientToolExecutionContext;
import com.healthy.agent.knowledge.rag.KnowledgeRagResponse;
import com.healthy.agent.state.CandidateResultSet;
import com.healthy.agent.trace.AgentTraceRecorder;
import com.healthy.agent.trace.AgentTraceStepType;

import java.util.Map;
import java.util.function.Supplier;

/** Uniform decorator for all patient ToolCallbacks. It never records tool input or full output. */
final class PatientToolTraceInterceptor {
    private final ObjectMapper objectMapper;

    PatientToolTraceInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String intercept(
            String name,
            PatientToolExecutionContext context,
            Supplier<String> invocation
    ) {
        context.recordTool(name);
        AgentTraceRecorder.StepHandle step = context.trace().start(
                AgentTraceStepType.TOOL, name);
        CandidateResultSet previousResultSet = context.lastResultSet();
        PendingActionView previousPendingAction = context.pendingAction();
        KnowledgeRagResponse previousRag = context.ragResponse();
        try {
            String result = invocation.get();
            ToolTraceOutcome outcome = outcome(
                    result, context, previousResultSet, previousPendingAction, previousRag);
            if (outcome.succeeded()) step.success(outcome.summary(), outcome.details());
            else step.failure(outcome.summary(), outcome.details());
            return result;
        } catch (RuntimeException exception) {
            step.failure("Tool 执行失败", Map.of(
                    "errorType", exception.getClass().getSimpleName()));
            throw exception;
        }
    }

    private ToolTraceOutcome outcome(
            String result,
            PatientToolExecutionContext context,
            CandidateResultSet previousResultSet,
            PendingActionView previousPendingAction,
            KnowledgeRagResponse previousRag
    ) {
        KnowledgeRagResponse rag = context.ragResponse();
        if (rag != null && rag != previousRag) {
            int chunks = rag.citations().size();
            return ToolTraceOutcome.success(
                    "知识库回答使用 " + chunks + " 个 Chunk",
                    Map.of("chunkCount", chunks));
        }
        PendingActionView pendingAction = context.pendingAction();
        if (pendingAction != null && pendingAction != previousPendingAction) {
            return ToolTraceOutcome.success(
                    "已创建 " + pendingAction.type().name() + " 确认卡",
                    Map.of("actionType", pendingAction.type().name()));
        }
        CandidateResultSet resultSet = context.lastResultSet();
        if (resultSet != null && resultSet != previousResultSet) {
            int count = resultSet.candidates().size();
            return ToolTraceOutcome.success(
                    "返回 " + count + " 个 " + resultSet.type().name() + " 候选项",
                    Map.of("candidateType", resultSet.type().name(), "resultCount", count));
        }
        try {
            JsonNode root = objectMapper.readTree(result == null ? "" : result);
            if (root != null && root.isObject()) {
                if (root.has("ok") && !root.path("ok").asBoolean()) {
                    String errorType = root.path("error").path("type").asText("TOOL_ERROR");
                    return ToolTraceOutcome.failure(
                            "Tool 返回失败：" + errorType,
                            Map.of("errorType", errorType));
                }
                String selectionStatus = root.path("status").asText("");
                if (!selectionStatus.isBlank()) {
                    return ToolTraceOutcome.success(
                            "候选选择结果：" + selectionStatus,
                            Map.of("selectionStatus", selectionStatus));
                }
            }
        } catch (JsonProcessingException ignored) {
            // Some returnDirect tools intentionally return plain display text.
        }
        return ToolTraceOutcome.success("Tool 执行成功", Map.of());
    }

    private record ToolTraceOutcome(
            boolean succeeded,
            String summary,
            Map<String, Object> details
    ) {
        private static ToolTraceOutcome success(String summary, Map<String, Object> details) {
            return new ToolTraceOutcome(true, summary, details);
        }

        private static ToolTraceOutcome failure(String summary, Map<String, Object> details) {
            return new ToolTraceOutcome(false, summary, details);
        }
    }
}
