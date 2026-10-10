package com.healthy.agent.action;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.state.AgentStateService;
import com.healthy.agent.state.Candidate;
import com.healthy.agent.state.CandidateType;
import com.healthy.agent.tool.ToolError;
import com.healthy.agent.tool.ToolExecutionResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class PatientActionService {
    public static final String PREPARE_CANCEL_APPOINTMENT = "prepare_cancel_appointment";
    public static final String PREPARE_CREATE_APPOINTMENT = "prepare_create_appointment";
    public static final String PREPARE_JOIN_WAITLIST = "prepare_join_waitlist";
    public static final String PREPARE_CANCEL_WAITLIST = "prepare_cancel_waitlist";
    public static final String PREPARE_CONFIRM_WAITLIST = "prepare_confirm_waitlist";

    private static final int LOCK_STRIPES = 64;

    private final PatientActionHandlerRegistry handlerRegistry;
    private final InMemoryPendingActionStore store;
    private final AgentStateService stateService;
    private final Clock clock;
    private final Object[] preparationLocks = new Object[LOCK_STRIPES];

    @Autowired
    public PatientActionService(
            PatientActionHandlerRegistry handlerRegistry,
            InMemoryPendingActionStore store,
            AgentStateService stateService
    ) {
        this(handlerRegistry, store, stateService, Clock.systemUTC());
    }

    PatientActionService(
            PatientActionHandlerRegistry handlerRegistry,
            InMemoryPendingActionStore store,
            AgentStateService stateService,
            Clock clock
    ) {
        this.handlerRegistry = handlerRegistry;
        this.store = store;
        this.stateService = stateService;
        this.clock = clock;
        for (int index = 0; index < preparationLocks.length; index++) {
            preparationLocks[index] = new Object();
        }
    }

    public ActionPreparationResult prepareCancellation(
            String conversationId,
            Candidate candidate,
            String authorization,
            long userId
    ) {
        if (candidate == null || candidate.type() != CandidateType.APPOINTMENT
                || candidate.businessId() < 1 || !"BOOKED".equals(candidate.status())) {
            return ActionPreparationResult.failed(invalid(
                    "取消预约前必须从预约 ResultSet 中选择唯一的 BOOKED 预约"));
        }
        return prepare(
                conversationId,
                PatientActionType.CANCEL_APPOINTMENT,
                new CancelAppointmentPayload(candidate.businessId()),
                authorization,
                userId);
    }

    public ActionPreparationResult prepareCreation(
            String conversationId,
            Candidate candidate,
            String authorization,
            long userId
    ) {
        if (candidate == null || candidate.type() != CandidateType.SCHEDULE_SLOT
                || candidate.businessId() < 1 || candidate.doctorId() == null
                || candidate.doctorId() < 1) {
            return ActionPreparationResult.failed(invalid(
                    "创建预约前必须从号源 ResultSet 中选择唯一的真实号源"));
        }
        LocalDate parsedDate;
        try {
            parsedDate = LocalDate.parse(candidate.date());
        } catch (DateTimeParseException | NullPointerException exception) {
            return ActionPreparationResult.failed(invalid("号源日期无效，请重新查询号源"));
        }
        return prepare(
                conversationId,
                PatientActionType.CREATE_APPOINTMENT,
                new CreateAppointmentPayload(
                        candidate.businessId(), candidate.doctorId(), parsedDate,
                        UUID.randomUUID().toString()),
                authorization,
                userId);
    }

    public ActionPreparationResult prepareJoinWaitlist(
            String conversationId,
            Candidate candidate,
            String authorization,
            long userId
    ) {
        if (candidate == null || candidate.type() != CandidateType.SCHEDULE_SLOT
                || candidate.businessId() < 1 || candidate.doctorId() == null
                || candidate.doctorId() < 1 || candidate.remainingCapacity() == null
                || candidate.remainingCapacity() != 0) {
            return ActionPreparationResult.failed(invalid(
                    "加入候补前必须从号源 ResultSet 中选择唯一且余号为 0 的真实班次"));
        }
        LocalDate parsedDate;
        try {
            parsedDate = LocalDate.parse(candidate.date());
        } catch (DateTimeParseException | NullPointerException exception) {
            return ActionPreparationResult.failed(invalid("号源日期无效，请重新查询号源"));
        }
        return prepare(
                conversationId,
                PatientActionType.JOIN_WAITLIST,
                new JoinWaitlistPayload(
                        candidate.businessId(), candidate.doctorId(), parsedDate),
                authorization,
                userId);
    }

    public ActionPreparationResult prepareCancelWaitlist(
            String conversationId,
            Candidate candidate,
            String authorization,
            long userId
    ) {
        if (candidate == null || candidate.type() != CandidateType.WAITLIST
                || candidate.businessId() < 1 || !"WAITING".equals(candidate.status())) {
            return ActionPreparationResult.failed(invalid(
                    "取消候补前必须从候补 ResultSet 中选择唯一的 WAITING 记录"));
        }
        return prepare(
                conversationId,
                PatientActionType.CANCEL_WAITLIST,
                new CancelWaitlistPayload(candidate.businessId()),
                authorization,
                userId);
    }

    public ActionPreparationResult prepareConfirmWaitlist(
            String conversationId,
            Candidate candidate,
            String authorization,
            long userId
    ) {
        if (candidate == null || candidate.type() != CandidateType.WAITLIST
                || candidate.businessId() < 1 || !"OFFERED".equals(candidate.status())) {
            return ActionPreparationResult.failed(invalid(
                    "确认候补名额前必须从候补 ResultSet 中选择唯一的 OFFERED 记录"));
        }
        return prepare(
                conversationId,
                PatientActionType.CONFIRM_WAITLIST,
                new ConfirmWaitlistPayload(candidate.businessId()),
                authorization,
                userId);
    }

    public PatientActionResponse confirm(
            String actionId,
            String conversationId,
            long userId,
            String authorization
    ) {
        Instant now = clock.instant();
        PendingPatientAction action = store.transition(
                actionId, userId, conversationId,
                PatientActionStatus.PENDING, PatientActionStatus.EXECUTING,
                now, "正在执行操作");

        PatientActionResponse response = null;
        try {
            ActionExecutionResult execution;
            try {
                execution = Objects.requireNonNull(handlerRegistry.execute(
                        action.type(), action.payload(),
                        new PatientActionContext(userId, authorization, now)),
                        "Patient action handler returned no execution result");
            } catch (RuntimeException exception) {
                response = store.complete(
                        actionId, PatientActionStatus.FAILED, "操作执行失败").toResponse();
                throw exception;
            }

            PatientActionStatus finalStatus = execution.succeeded()
                    ? PatientActionStatus.SUCCEEDED : PatientActionStatus.FAILED;
            response = store.complete(
                    actionId, finalStatus, execution.message()).toResponse();
            propagateAuthorizationFailure(execution.error());
            return response;
        } finally {
            if (response == null) {
                stateService.clearPendingAction(conversationId, actionId);
            } else {
                stateService.completeAction(conversationId, actionId, response);
            }
        }
    }

    public PatientActionResponse reject(
            String actionId,
            String conversationId,
            long userId
    ) {
        PatientActionResponse response = store.transition(
                actionId, userId, conversationId,
                PatientActionStatus.PENDING, PatientActionStatus.REJECTED,
                clock.instant(), "已放弃本次操作").toResponse();
        stateService.completeAction(conversationId, actionId, response);
        return response;
    }

    public Optional<PendingActionView> activeAction(String conversationId) {
        return stateService.getPendingAction(conversationId);
    }

    public String confirmationMessage(PendingActionView view) {
        StringBuilder message = new StringBuilder(view.preview().description());
        for (ActionPreviewField field : view.preview().fields()) {
            if (field.value() == null || field.value().isBlank()) continue;
            message.append('\n').append(field.label()).append("：").append(field.value());
        }
        message.append("\n尚未执行，请点击确认卡片中的“")
                .append(view.preview().confirmButtonText()).append("”按钮。");
        return message.toString();
    }

    private ActionPreparationResult prepare(
            String conversationId,
            PatientActionType type,
            PatientActionPayload payload,
            String authorization,
            long userId
    ) {
        Optional<PendingActionView> existing = activeAction(conversationId);
        if (existing.isPresent()) return ActionPreparationResult.ready(existing.get());

        Instant now = clock.instant();
        ActionHandlerPreparationResult<?> preparation = handlerRegistry.prepare(
                type, payload, new PatientActionContext(userId, authorization, now));
        if (!preparation.ready()) return ActionPreparationResult.failed(preparation.failure());

        synchronized (lockFor(conversationId)) {
            existing = activeAction(conversationId);
            if (existing.isPresent()) return ActionPreparationResult.ready(existing.get());

            PreparedPatientAction<?> prepared = preparation.preparedAction();
            PendingPatientAction action = new PendingPatientAction(
                    UUID.randomUUID().toString(),
                    userId,
                    conversationId,
                    type,
                    prepared.payload(),
                    PatientActionStatus.PENDING,
                    now,
                    now.plus(prepared.confirmationTtl()),
                    prepared.preview(),
                    "等待患者确认");
            PendingActionView view = store.save(action).toView();
            stateService.prepareAction(conversationId, view);
            return ActionPreparationResult.ready(view);
        }
    }

    private Object lockFor(String conversationId) {
        return preparationLocks[Math.floorMod(conversationId.hashCode(), LOCK_STRIPES)];
    }

    private void propagateAuthorizationFailure(ToolError error) {
        if (error != null && "AUTH_REQUIRED".equals(error.type())) {
            throw new AgentException(AgentErrorCode.UNAUTHORIZED);
        }
        if (error != null && "FORBIDDEN".equals(error.type())) {
            throw new AgentException(AgentErrorCode.FORBIDDEN);
        }
    }

    private ToolExecutionResult invalid(String message) {
        return ToolExecutionResult.failure(new ToolError(
                400, 40001, "INVALID_ARGUMENT", message, false));
    }
}
