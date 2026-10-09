package com.healthy.agent.action;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class PatientActionHandlerRegistry {
    private final Map<PatientActionType, PatientActionHandler<?>> handlers;

    public PatientActionHandlerRegistry(List<PatientActionHandler<?>> handlers) {
        EnumMap<PatientActionType, PatientActionHandler<?>> registered =
                new EnumMap<>(PatientActionType.class);
        for (PatientActionHandler<?> handler : handlers) {
            PatientActionHandler<?> previous = registered.putIfAbsent(handler.type(), handler);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate patient action handler: " + handler.type());
            }
        }
        this.handlers = Map.copyOf(registered);
    }

    public ActionHandlerPreparationResult<?> prepare(
            PatientActionType type,
            PatientActionPayload payload,
            PatientActionContext context
    ) {
        return prepareTyped(requireHandler(type), payload, context);
    }

    public ActionExecutionResult execute(
            PatientActionType type,
            PatientActionPayload payload,
            PatientActionContext context
    ) {
        return executeTyped(requireHandler(type), payload, context);
    }

    private PatientActionHandler<?> requireHandler(PatientActionType type) {
        PatientActionHandler<?> handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalStateException("No patient action handler registered for " + type);
        }
        return handler;
    }

    private <P extends PatientActionPayload> ActionHandlerPreparationResult<P> prepareTyped(
            PatientActionHandler<P> handler,
            PatientActionPayload payload,
            PatientActionContext context
    ) {
        return handler.prepare(castPayload(handler, payload), context);
    }

    private <P extends PatientActionPayload> ActionExecutionResult executeTyped(
            PatientActionHandler<P> handler,
            PatientActionPayload payload,
            PatientActionContext context
    ) {
        return handler.execute(castPayload(handler, payload), context);
    }

    private <P extends PatientActionPayload> P castPayload(
            PatientActionHandler<P> handler,
            PatientActionPayload payload
    ) {
        if (!handler.payloadType().isInstance(payload)) {
            throw new IllegalStateException(
                    "Payload type mismatch for " + handler.type() + ": "
                            + payload.getClass().getSimpleName());
        }
        return handler.payloadType().cast(payload);
    }
}
