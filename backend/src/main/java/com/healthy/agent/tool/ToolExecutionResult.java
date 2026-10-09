package com.healthy.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolExecutionResult(
        boolean ok,
        JsonNode data,
        ToolError error
) {
    public static ToolExecutionResult success(JsonNode data) {
        return new ToolExecutionResult(true, data, null);
    }

    public static ToolExecutionResult failure(ToolError error) {
        return new ToolExecutionResult(false, null, error);
    }
}
