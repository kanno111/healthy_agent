package com.healthy.agent.action;

import com.healthy.agent.tool.ToolExecutionResult;

public record ActionPreparationResult(
        PendingActionView pendingAction,
        ToolExecutionResult failure
) {
    public static ActionPreparationResult ready(PendingActionView pendingAction) {
        return new ActionPreparationResult(pendingAction, null);
    }

    public static ActionPreparationResult failed(ToolExecutionResult failure) {
        return new ActionPreparationResult(null, failure);
    }

    public boolean ready() {
        return pendingAction != null;
    }
}
