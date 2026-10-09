package com.healthy.agent.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.healthy.agent.tool.HealthyApiClient;
import com.healthy.agent.tool.ToolError;
import com.healthy.agent.tool.ToolExecutionResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class CreateAppointmentActionHandler
        implements PatientActionHandler<CreateAppointmentPayload> {
    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(10);

    private final HealthyApiClient healthyApiClient;

    public CreateAppointmentActionHandler(HealthyApiClient healthyApiClient) {
        this.healthyApiClient = healthyApiClient;
    }

    @Override
    public PatientActionType type() {
        return PatientActionType.CREATE_APPOINTMENT;
    }

    @Override
    public Class<CreateAppointmentPayload> payloadType() {
        return CreateAppointmentPayload.class;
    }

    @Override
    public ActionHandlerPreparationResult<CreateAppointmentPayload> prepare(
            CreateAppointmentPayload payload,
            PatientActionContext context
    ) {
        ToolExecutionResult doctorLookup = doctor(payload, context.authorization());
        if (!doctorLookup.ok()) {
            return ActionHandlerPreparationResult.failed(doctorLookup);
        }
        ToolExecutionResult slotLookup = slots(payload, context.authorization());
        if (!slotLookup.ok()) {
            return ActionHandlerPreparationResult.failed(slotLookup);
        }
        JsonNode slot = findSlot(slotLookup.data(), payload.scheduleSlotId());
        ToolExecutionResult validation = validateAvailableSlot(slot);
        if (validation != null) {
            return ActionHandlerPreparationResult.failed(validation);
        }

        JsonNode doctor = doctorLookup.data();
        PatientActionPreview preview = new PatientActionPreview(
                "确认创建预约",
                "创建预约属于写操作，请核对以下号源信息后再确认。",
                "确认预约",
                "暂不预约",
                List.of(
                        field("doctorName", "医生", text(doctor, "name")),
                        field("departmentName", "科室", text(doctor, "departmentName")),
                        field("scheduleDate", "日期", text(slot, "scheduleDate")),
                        field("sessionName", "时段", text(slot, "sessionName")),
                        field("time", "时间", timeRange(slot)),
                        field("remainingCapacity", "剩余号源",
                                Integer.toString(slot.path("remainingCapacity").asInt()))
                ));
        return ActionHandlerPreparationResult.ready(new PreparedPatientAction<>(
                payload, preview, CONFIRMATION_TTL));
    }

    @Override
    public ActionExecutionResult execute(
            CreateAppointmentPayload payload,
            PatientActionContext context
    ) {
        ToolExecutionResult slotLookup = slots(payload, context.authorization());
        if (!slotLookup.ok()) {
            return ActionExecutionResult.failed(slotLookup);
        }
        JsonNode slot = findSlot(slotLookup.data(), payload.scheduleSlotId());
        ToolExecutionResult validation = validateAvailableSlot(slot);
        if (validation != null) {
            return ActionExecutionResult.failed(validation);
        }

        Map<String, Object> body = Map.of(
                "scheduleSlotId", payload.scheduleSlotId(),
                "requestId", payload.requestId());
        ToolExecutionResult creation = healthyApiClient.post(
                "/api/user/appointments", body, context.authorization());
        if (!creation.ok() && creation.error() != null
                && creation.error().retryable()) {
            creation = healthyApiClient.post(
                    "/api/user/appointments", body, context.authorization());
        }
        if (!creation.ok()) {
            return ActionExecutionResult.failed(creation);
        }
        return ActionExecutionResult.succeeded("预约已成功创建");
    }

    private ToolExecutionResult doctor(
            CreateAppointmentPayload payload,
            String authorization
    ) {
        return healthyApiClient.get(
                "/api/user/doctors/" + payload.doctorId(), authorization);
    }

    private ToolExecutionResult slots(
            CreateAppointmentPayload payload,
            String authorization
    ) {
        return healthyApiClient.get(
                "/api/user/doctors/" + payload.doctorId() + "/schedule-slots",
                Map.of(
                        "startDate", payload.scheduleDate(),
                        "endDate", payload.scheduleDate()),
                authorization);
    }

    private JsonNode findSlot(JsonNode data, long scheduleSlotId) {
        if (data == null || !data.isArray()) {
            return null;
        }
        for (JsonNode slot : data) {
            if (slot.path("id").canConvertToLong()
                    && slot.path("id").longValue() == scheduleSlotId) {
                return slot;
            }
        }
        return null;
    }

    private ToolExecutionResult validateAvailableSlot(JsonNode slot) {
        if (slot == null) {
            return failure(409, 40900, "BUSINESS_STATE_CHANGED",
                    "该号源已关闭、结束或不再可预约", false);
        }
        if (slot.path("remainingCapacity").asInt(0) < 1) {
            return failure(409, 40900, "BUSINESS_STATE_CHANGED",
                    "该班次已经没有剩余号源", false);
        }
        return null;
    }

    private ActionPreviewField field(String key, String label, String value) {
        return new ActionPreviewField(key, label, safe(value));
    }

    private String timeRange(JsonNode slot) {
        return shortTime(text(slot, "startTime")) + "–" + shortTime(text(slot, "endTime"));
    }

    private String shortTime(String value) {
        if (value == null || value.isBlank()) {
            return "未提供";
        }
        return value.length() >= 5 ? value.substring(0, 5) : value;
    }

    private String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "未提供" : value;
    }

    private ToolExecutionResult failure(
            int httpStatus,
            int code,
            String type,
            String message,
            boolean retryable
    ) {
        return ToolExecutionResult.failure(new ToolError(
                httpStatus, code, type, message, retryable));
    }
}
