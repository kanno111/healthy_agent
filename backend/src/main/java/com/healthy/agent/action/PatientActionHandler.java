package com.healthy.agent.action;

public interface PatientActionHandler<P extends PatientActionPayload> {
    PatientActionType type();

    Class<P> payloadType();

    ActionHandlerPreparationResult<P> prepare(P payload, PatientActionContext context);

    ActionExecutionResult execute(P payload, PatientActionContext context);
}
