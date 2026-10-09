package com.healthy.agent.action;

import com.healthy.agent.tool.ToolError;
import com.healthy.agent.tool.ToolExecutionResult;

public record ActionExecutionResult(
        boolean succeeded,
        String message,
        ToolError error
) {
    public static ActionExecutionResult succeeded(String message) {
        return new ActionExecutionResult(true, message, null);
    }

    public static ActionExecutionResult failed(String message) {
        return new ActionExecutionResult(false, message, null);
    }

    public static ActionExecutionResult failed(ToolExecutionResult result) {
        ToolError error = result.error();
        String message = error == null ? "医院业务请求失败" : error.message();
        return new ActionExecutionResult(false, message, error);
    }
}
