package com.healthy.agent.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.healthy.agent.tool.HealthyApiClient;
import com.healthy.agent.tool.ToolError;
import com.healthy.agent.tool.ToolExecutionResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class CancelAppointmentActionHandler
        implements PatientActionHandler<CancelAppointmentPayload> {
    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(10);
    private static final String APPOINTMENTS_PATH = "/api/user/appointments";

    private final HealthyApiClient healthyApiClient;

    public CancelAppointmentActionHandler(HealthyApiClient healthyApiClient) {
        this.healthyApiClient = healthyApiClient;
    }

    @Override
    public PatientActionType type() {
        return PatientActionType.CANCEL_APPOINTMENT;
    }

    @Override
    public Class<CancelAppointmentPayload> payloadType() {
        return CancelAppointmentPayload.class;
    }

    @Override
    public ActionHandlerPreparationResult<CancelAppointmentPayload> prepare(
            CancelAppointmentPayload payload,
            PatientActionContext context
    ) {
        if (payload.appointmentId() < 1) {
            return ActionHandlerPreparationResult.failed(invalid(
                    "appointmentId 必须是来自预约查询结果的正整数"));
        }

        ToolExecutionResult lookup = healthyApiClient.get(
                APPOINTMENTS_PATH, context.authorization());
        if (!lookup.ok()) {
            return ActionHandlerPreparationResult.failed(lookup);
        }
        JsonNode appointment = findAppointment(lookup.data(), payload.appointmentId());
        if (appointment == null) {
            return ActionHandlerPreparationResult.failed(failure(
                    404, 40400, "RESOURCE_NOT_FOUND",
                    "没有找到属于当前患者的预约", false));
        }
        String status = text(appointment, "status");
        if (!"BOOKED".equals(status)) {
            return ActionHandlerPreparationResult.failed(failure(
                    409, 40900, "BUSINESS_STATE_CHANGED",
                    "该预约当前状态为 " + displayStatus(status) + "，不能取消", false));
        }

        PatientActionPreview preview = new PatientActionPreview(
                "确认取消预约",
                "取消预约属于写操作，请核对以下信息后再确认。",
                "确认取消",
                "暂不取消",
                List.of(
                        field("doctorName", "医生", text(appointment, "doctorName")),
                        field("departmentName", "科室", text(appointment, "departmentName")),
                        field("scheduleDate", "日期", text(appointment, "scheduleDate")),
                        field("sessionName", "时段", text(appointment, "sessionName")),
                        field("time", "时间", timeRange(appointment)),
                        field("appointmentNo", "预约号", text(appointment, "appointmentNo"))
                ));
        return ActionHandlerPreparationResult.ready(new PreparedPatientAction<>(
                payload, preview, CONFIRMATION_TTL));
    }

    @Override
    public ActionExecutionResult execute(
            CancelAppointmentPayload payload,
            PatientActionContext context
    ) {
        ToolExecutionResult lookup = healthyApiClient.get(
                APPOINTMENTS_PATH, context.authorization());
        if (!lookup.ok()) {
            return ActionExecutionResult.failed(lookup);
        }
        JsonNode appointment = findAppointment(lookup.data(), payload.appointmentId());
        if (appointment == null) {
            return ActionExecutionResult.failed("预约不存在或已不属于当前患者");
        }
        String status = text(appointment, "status");
        if ("CANCELLED".equals(status)) {
            return ActionExecutionResult.succeeded("该预约已经取消，无需重复操作");
        }
        if (!"BOOKED".equals(status)) {
            return ActionExecutionResult.failed(
                    "预约当前状态为 " + displayStatus(status) + "，不能取消");
        }

        ToolExecutionResult cancellation = healthyApiClient.patch(
                APPOINTMENTS_PATH + "/" + payload.appointmentId() + "/cancel",
                context.authorization());
        if (!cancellation.ok()) {
            return ActionExecutionResult.failed(cancellation);
        }
        return ActionExecutionResult.succeeded("预约已成功取消");
    }

    private JsonNode findAppointment(JsonNode data, long appointmentId) {
        if (data == null || !data.isArray()) {
            return null;
        }
        for (JsonNode appointment : data) {
            if (appointment.path("id").canConvertToLong()
                    && appointment.path("id").longValue() == appointmentId) {
                return appointment;
            }
        }
        return null;
    }

    private ActionPreviewField field(String key, String label, String value) {
        return new ActionPreviewField(key, label, safe(value));
    }

    private String timeRange(JsonNode appointment) {
        String start = shortTime(text(appointment, "startTime"));
        String end = shortTime(text(appointment, "endTime"));
        if (start == null && end == null) {
            return null;
        }
        return safe(start) + "–" + safe(end);
    }

    private String shortTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() >= 5 ? value.substring(0, 5) : value;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "未提供" : value;
    }

    private String displayStatus(String status) {
        if (status == null || status.isBlank()) {
            return "未知";
        }
        return switch (status) {
            case "BOOKED" -> "已预约";
            case "CANCELLED" -> "已取消";
            case "COMPLETED" -> "已完成";
            default -> status;
        };
    }

    private ToolExecutionResult invalid(String message) {
        return failure(400, 40001, "INVALID_ARGUMENT", message, false);
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
