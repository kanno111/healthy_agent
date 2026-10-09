package com.healthy.agent.action;

import com.healthy.agent.tool.ToolExecutionResult;

public record ActionHandlerPreparationResult<P extends PatientActionPayload>(
        PreparedPatientAction<P> preparedAction,
        ToolExecutionResult failure
) {
    public static <P extends PatientActionPayload> ActionHandlerPreparationResult<P> ready(
            PreparedPatientAction<P> preparedAction
    ) {
        return new ActionHandlerPreparationResult<>(preparedAction, null);
    }

    public static <P extends PatientActionPayload> ActionHandlerPreparationResult<P> failed(
            ToolExecutionResult failure
    ) {
        return new ActionHandlerPreparationResult<>(null, failure);
    }

    public boolean ready() {
        return preparedAction != null;
    }
}
