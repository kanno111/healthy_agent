package com.healthy.agent.tool;

public record ToolError(
        int httpStatus,
        int code,
        String type,
        String message,
        boolean retryable
) {
}
