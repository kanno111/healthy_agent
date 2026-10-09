package com.healthy.agent.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.healthy.agent.tool.HealthyApiClient;
import com.healthy.agent.tool.ToolExecutionResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class ConfirmWaitlistActionHandler
        implements PatientActionHandler<ConfirmWaitlistPayload> {
    private static final Duration MAX_CONFIRMATION_TTL = Duration.ofMinutes(10);

    private final HealthyApiClient healthyApiClient;

    public ConfirmWaitlistActionHandler(HealthyApiClient healthyApiClient) {
        this.healthyApiClient = healthyApiClient;
    }

    @Override
    public PatientActionType type() {
        return PatientActionType.CONFIRM_WAITLIST;
    }

    @Override
    public Class<ConfirmWaitlistPayload> payloadType() {
        return ConfirmWaitlistPayload.class;
    }

    @Override
    public ActionHandlerPreparationResult<ConfirmWaitlistPayload> prepare(
            ConfirmWaitlistPayload payload,
            PatientActionContext context
    ) {
        if (payload.waitlistId() < 1) {
            return ActionHandlerPreparationResult.failed(
                    WaitlistActionSupport.invalid("waitlistId 必须来自候补查询结果"));
        }
        ToolExecutionResult lookup = list(context.authorization());
        if (!lookup.ok()) return ActionHandlerPreparationResult.failed(lookup);
        JsonNode waitlist = WaitlistActionSupport.findWaitlist(
                lookup.data(), payload.waitlistId());
        if (waitlist == null) {
            return ActionHandlerPreparationResult.failed(
                    WaitlistActionSupport.notFound("没有找到属于当前患者的候补记录"));
        }
        ToolExecutionResult validation = validateOffer(waitlist, context.now());
        if (validation != null) return ActionHandlerPreparationResult.failed(validation);

        Instant offerExpiry = WaitlistActionSupport.offerExpiry(waitlist);
        Duration remaining = Duration.between(context.now(), offerExpiry);
        Duration ttl = remaining.compareTo(MAX_CONFIRMATION_TTL) < 0
                ? remaining : MAX_CONFIRMATION_TTL;
        PatientActionPreview preview = WaitlistActionSupport.preview(
                waitlist,
                "确认候补名额",
                "确认候补名额会立即创建预约，请在服务端提供的截止时间前确认。",
                "确认名额并创建预约",
                "暂不确认",
                true);
        return ActionHandlerPreparationResult.ready(new PreparedPatientAction<>(
                payload, preview, ttl));
    }

    @Override
    public ActionExecutionResult execute(
            ConfirmWaitlistPayload payload,
            PatientActionContext context
    ) {
        ToolExecutionResult lookup = list(context.authorization());
        if (!lookup.ok()) return ActionExecutionResult.failed(lookup);
        JsonNode waitlist = WaitlistActionSupport.findWaitlist(
                lookup.data(), payload.waitlistId());
        if (waitlist == null) return ActionExecutionResult.failed("候补不存在或已不属于当前患者");
        String status = WaitlistActionSupport.text(waitlist, "status");
        if ("CONFIRMED".equals(status)) {
            return ActionExecutionResult.succeeded("该候补名额已经确认，预约已创建");
        }
        ToolExecutionResult validation = validateOffer(waitlist, context.now());
        if (validation != null) return ActionExecutionResult.failed(validation);

        ToolExecutionResult confirmed = healthyApiClient.post(
                WaitlistActionSupport.WAITLISTS_PATH + "/" + payload.waitlistId() + "/confirm",
                context.authorization());
        if (confirmed.ok()) {
            return ActionExecutionResult.succeeded("候补名额已确认，预约已成功创建");
        }
        if (confirmed.error() != null && confirmed.error().retryable()) {
            ToolExecutionResult refreshed = list(context.authorization());
            JsonNode current = refreshed.ok() ? WaitlistActionSupport.findWaitlist(
                    refreshed.data(), payload.waitlistId()) : null;
            if (current != null && "CONFIRMED".equals(
                    WaitlistActionSupport.text(current, "status"))) {
                return ActionExecutionResult.succeeded(
                        "候补名额已确认，预约已创建（已通过最新候补记录确认）");
            }
            return ActionExecutionResult.failed(
                    "候补名额的最终确认结果暂时无法确定，请立即重新查询我的候补和预约，避免重复操作");
        }
        return ActionExecutionResult.failed(confirmed);
    }

    private ToolExecutionResult validateOffer(JsonNode waitlist, Instant now) {
        String status = WaitlistActionSupport.text(waitlist, "status");
        if (!"OFFERED".equals(status)) {
            return WaitlistActionSupport.stateChanged(
                    "候补当前状态为 " + WaitlistActionSupport.displayStatus(status)
                            + "，没有可确认的名额");
        }
        Instant offerExpiry = WaitlistActionSupport.offerExpiry(waitlist);
        if (offerExpiry == null) {
            return WaitlistActionSupport.stateChanged("候补名额没有有效的确认截止时间");
        }
        if (!offerExpiry.isAfter(now)) {
            return WaitlistActionSupport.stateChanged("候补名额确认时间已经结束");
        }
        return null;
    }

    private ToolExecutionResult list(String authorization) {
        return healthyApiClient.get(WaitlistActionSupport.WAITLISTS_PATH, authorization);
    }
}
