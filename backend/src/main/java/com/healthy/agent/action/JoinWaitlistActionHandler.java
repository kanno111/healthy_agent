package com.healthy.agent.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.healthy.agent.tool.HealthyApiClient;
import com.healthy.agent.tool.ToolExecutionResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class JoinWaitlistActionHandler implements PatientActionHandler<JoinWaitlistPayload> {
    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(10);

    private final HealthyApiClient healthyApiClient;

    public JoinWaitlistActionHandler(HealthyApiClient healthyApiClient) {
        this.healthyApiClient = healthyApiClient;
    }

    @Override
    public PatientActionType type() {
        return PatientActionType.JOIN_WAITLIST;
    }

    @Override
    public Class<JoinWaitlistPayload> payloadType() {
        return JoinWaitlistPayload.class;
    }

    @Override
    public ActionHandlerPreparationResult<JoinWaitlistPayload> prepare(
            JoinWaitlistPayload payload,
            PatientActionContext context
    ) {
        ToolExecutionResult doctorLookup = doctor(payload, context.authorization());
        if (!doctorLookup.ok()) return ActionHandlerPreparationResult.failed(doctorLookup);
        ToolExecutionResult slotLookup = slots(payload, context.authorization());
        if (!slotLookup.ok()) return ActionHandlerPreparationResult.failed(slotLookup);
        JsonNode slot = findSlot(slotLookup.data(), payload.scheduleSlotId());
        ToolExecutionResult validation = validateFullSlot(slot);
        if (validation != null) return ActionHandlerPreparationResult.failed(validation);

        JsonNode doctor = doctorLookup.data();
        PatientActionPreview preview = new PatientActionPreview(
                "确认加入候补",
                "加入候补属于写操作。只有班次仍无余号时才能加入，请核对后确认。",
                "确认加入候补",
                "暂不候补",
                List.of(
                        WaitlistActionSupport.field("doctorName", "医生",
                                WaitlistActionSupport.text(doctor, "name")),
                        WaitlistActionSupport.field("departmentName", "科室",
                                WaitlistActionSupport.text(doctor, "departmentName")),
                        WaitlistActionSupport.field("scheduleDate", "日期",
                                WaitlistActionSupport.text(slot, "scheduleDate")),
                        WaitlistActionSupport.field("sessionName", "时段",
                                WaitlistActionSupport.text(slot, "sessionName")),
                        WaitlistActionSupport.field("time", "时间",
                                WaitlistActionSupport.timeRange(slot)),
                        WaitlistActionSupport.field("remainingCapacity", "剩余号源", "0")
                ));
        return ActionHandlerPreparationResult.ready(new PreparedPatientAction<>(
                payload, preview, CONFIRMATION_TTL));
    }

    @Override
    public ActionExecutionResult execute(
            JoinWaitlistPayload payload,
            PatientActionContext context
    ) {
        ToolExecutionResult slotLookup = slots(payload, context.authorization());
        if (!slotLookup.ok()) return ActionExecutionResult.failed(slotLookup);
        ToolExecutionResult validation = validateFullSlot(
                findSlot(slotLookup.data(), payload.scheduleSlotId()));
        if (validation != null) return ActionExecutionResult.failed(validation);

        ToolExecutionResult joined = healthyApiClient.post(
                WaitlistActionSupport.WAITLISTS_PATH,
                Map.of("scheduleSlotId", payload.scheduleSlotId()),
                context.authorization());
        if (joined.ok()) return ActionExecutionResult.succeeded("已成功加入候补");

        if (joined.error() != null && joined.error().retryable()) {
            ToolExecutionResult lookup = healthyApiClient.get(
                    WaitlistActionSupport.WAITLISTS_PATH, context.authorization());
            if (lookup.ok() && WaitlistActionSupport.findActiveForSlot(
                    lookup.data(), payload.scheduleSlotId()) != null) {
                return ActionExecutionResult.succeeded("已加入候补（已通过最新候补记录确认）");
            }
            return ActionExecutionResult.failed(
                    "加入候补的最终结果暂时无法确认，请重新查询我的候补后再决定是否操作");
        }
        return ActionExecutionResult.failed(joined);
    }

    private ToolExecutionResult doctor(JoinWaitlistPayload payload, String authorization) {
        return healthyApiClient.get(
                "/api/user/doctors/" + payload.doctorId(), authorization);
    }

    private ToolExecutionResult slots(JoinWaitlistPayload payload, String authorization) {
        return healthyApiClient.get(
                "/api/user/doctors/" + payload.doctorId() + "/schedule-slots",
                Map.of("startDate", payload.scheduleDate(), "endDate", payload.scheduleDate()),
                authorization);
    }

    private JsonNode findSlot(JsonNode data, long scheduleSlotId) {
        if (data == null || !data.isArray()) return null;
        for (JsonNode slot : data) {
            if (slot.path("id").canConvertToLong()
                    && slot.path("id").longValue() == scheduleSlotId) return slot;
        }
        return null;
    }

    private ToolExecutionResult validateFullSlot(JsonNode slot) {
        if (slot == null) {
            return WaitlistActionSupport.stateChanged("该班次已关闭、结束或不存在");
        }
        if (slot.path("remainingCapacity").asInt(-1) != 0) {
            return WaitlistActionSupport.stateChanged(
                    "该班次当前存在可预约号源，请直接预约，不需要加入候补");
        }
        return null;
    }
}
