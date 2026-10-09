package com.healthy.agent.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.healthy.agent.tool.ToolError;
import com.healthy.agent.tool.ToolExecutionResult;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

final class WaitlistActionSupport {
    static final String WAITLISTS_PATH = "/api/user/waitlists";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private WaitlistActionSupport() {
    }

    static JsonNode findWaitlist(JsonNode data, long waitlistId) {
        if (data == null || !data.isArray()) return null;
        for (JsonNode waitlist : data) {
            if (waitlist.path("id").canConvertToLong()
                    && waitlist.path("id").longValue() == waitlistId) {
                return waitlist;
            }
        }
        return null;
    }

    static JsonNode findActiveForSlot(JsonNode data, long scheduleSlotId) {
        if (data == null || !data.isArray()) return null;
        for (JsonNode waitlist : data) {
            if (waitlist.path("scheduleSlotId").canConvertToLong()
                    && waitlist.path("scheduleSlotId").longValue() == scheduleSlotId
                    && ("WAITING".equals(text(waitlist, "status"))
                    || "OFFERED".equals(text(waitlist, "status")))) {
                return waitlist;
            }
        }
        return null;
    }

    static PatientActionPreview preview(
            JsonNode waitlist,
            String title,
            String description,
            String confirmButtonText,
            String rejectButtonText,
            boolean includeOfferExpiry
    ) {
        List<ActionPreviewField> fields = new ArrayList<>(List.of(
                field("doctorName", "医生", text(waitlist, "doctorName")),
                field("departmentName", "科室", text(waitlist, "departmentName")),
                field("scheduleDate", "日期", text(waitlist, "scheduleDate")),
                field("sessionName", "时段", text(waitlist, "sessionName")),
                field("time", "时间", timeRange(waitlist)),
                field("status", "候补状态", displayStatus(text(waitlist, "status")))
        ));
        if (includeOfferExpiry) {
            fields.add(field("offerExpireTime", "名额确认截止时间",
                    displayDateTime(text(waitlist, "offerExpireTime"))));
        }
        return new PatientActionPreview(
                title, description, confirmButtonText, rejectButtonText, fields);
    }

    static ActionPreviewField field(String key, String label, String value) {
        return new ActionPreviewField(key, label, safe(value));
    }

    static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    static String timeRange(JsonNode node) {
        String start = shortTime(text(node, "startTime"));
        String end = shortTime(text(node, "endTime"));
        return start == null && end == null ? null : safe(start) + "–" + safe(end);
    }

    static Instant offerExpiry(JsonNode waitlist) {
        String value = text(waitlist, "offerExpireTime");
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // Healthy currently returns a local Asia/Shanghai datetime without an offset.
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // Fall through to the documented local datetime representation.
        }
        try {
            return LocalDateTime.parse(value).atZone(BUSINESS_ZONE).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    static String displayStatus(String status) {
        return switch (status == null ? "" : status) {
            case "WAITING" -> "排队中";
            case "OFFERED" -> "已获得限时名额";
            case "CONFIRMED" -> "已确认并生成预约";
            case "EXPIRED" -> "已过期";
            case "CANCELLED" -> "已取消";
            default -> safe(status);
        };
    }

    static ToolExecutionResult invalid(String message) {
        return failure(400, 40001, "INVALID_ARGUMENT", message);
    }

    static ToolExecutionResult notFound(String message) {
        return failure(404, 40400, "RESOURCE_NOT_FOUND", message);
    }

    static ToolExecutionResult stateChanged(String message) {
        return failure(409, 40900, "BUSINESS_STATE_CHANGED", message);
    }

    private static ToolExecutionResult failure(
            int httpStatus,
            int code,
            String type,
            String message
    ) {
        return ToolExecutionResult.failure(new ToolError(
                httpStatus, code, type, message, false));
    }

    private static String shortTime(String value) {
        if (value == null || value.isBlank()) return null;
        return value.length() >= 5 ? value.substring(0, 5) : value;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "未提供" : value;
    }

    private static String displayDateTime(String value) {
        return value == null || value.isBlank() ? null : value.replace('T', ' ');
    }
}
