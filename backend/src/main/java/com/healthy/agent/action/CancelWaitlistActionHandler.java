package com.healthy.agent.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.healthy.agent.tool.HealthyApiClient;
import com.healthy.agent.tool.ToolExecutionResult;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class CancelWaitlistActionHandler
        implements PatientActionHandler<CancelWaitlistPayload> {
    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(10);

    private final HealthyApiClient healthyApiClient;

    public CancelWaitlistActionHandler(HealthyApiClient healthyApiClient) {
        this.healthyApiClient = healthyApiClient;
    }

    @Override
    public PatientActionType type() {
        return PatientActionType.CANCEL_WAITLIST;
    }

    @Override
    public Class<CancelWaitlistPayload> payloadType() {
        return CancelWaitlistPayload.class;
    }

    @Override
    public ActionHandlerPreparationResult<CancelWaitlistPayload> prepare(
            CancelWaitlistPayload payload,
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
        String status = WaitlistActionSupport.text(waitlist, "status");
        if (!"WAITING".equals(status)) {
            return ActionHandlerPreparationResult.failed(
                    WaitlistActionSupport.stateChanged(
                            "候补当前状态为 " + WaitlistActionSupport.displayStatus(status)
                                    + "，不能取消"));
        }
        PatientActionPreview preview = WaitlistActionSupport.preview(
                waitlist, "确认取消候补", "取消候补属于写操作，请核对后确认。",
                "确认取消候补", "保留候补", false);
        return ActionHandlerPreparationResult.ready(new PreparedPatientAction<>(
                payload, preview, CONFIRMATION_TTL));
    }

    @Override
    public ActionExecutionResult execute(
            CancelWaitlistPayload payload,
            PatientActionContext context
    ) {
        ToolExecutionResult lookup = list(context.authorization());
        if (!lookup.ok()) return ActionExecutionResult.failed(lookup);
        JsonNode waitlist = WaitlistActionSupport.findWaitlist(
                lookup.data(), payload.waitlistId());
        if (waitlist == null) return ActionExecutionResult.failed("候补不存在或已不属于当前患者");
        String status = WaitlistActionSupport.text(waitlist, "status");
        if ("CANCELLED".equals(status)) {
            return ActionExecutionResult.succeeded("该候补已经取消，无需重复操作");
        }
        if (!"WAITING".equals(status)) {
            return ActionExecutionResult.failed(
                    "候补当前状态为 " + WaitlistActionSupport.displayStatus(status) + "，不能取消");
        }

        ToolExecutionResult cancelled = healthyApiClient.patch(
                WaitlistActionSupport.WAITLISTS_PATH + "/" + payload.waitlistId() + "/cancel",
                context.authorization());
        if (cancelled.ok()) return ActionExecutionResult.succeeded("候补已成功取消");
        if (cancelled.error() != null && cancelled.error().retryable()) {
            ToolExecutionResult refreshed = list(context.authorization());
            JsonNode current = refreshed.ok() ? WaitlistActionSupport.findWaitlist(
                    refreshed.data(), payload.waitlistId()) : null;
            if (current != null && "CANCELLED".equals(
                    WaitlistActionSupport.text(current, "status"))) {
                return ActionExecutionResult.succeeded("候补已取消（已通过最新候补记录确认）");
            }
            return ActionExecutionResult.failed(
                    "取消候补的最终结果暂时无法确认，请重新查询我的候补后再操作");
        }
        return ActionExecutionResult.failed(cancelled);
    }

    private ToolExecutionResult list(String authorization) {
        return healthyApiClient.get(WaitlistActionSupport.WAITLISTS_PATH, authorization);
    }
}
