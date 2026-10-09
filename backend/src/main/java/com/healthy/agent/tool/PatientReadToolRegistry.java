package com.healthy.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class PatientReadToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(PatientReadToolRegistry.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int DEFAULT_DOCTOR_PAGE_SIZE = 100;
    private static final int MAX_BATCH_DOCTORS = 30;

    private final HealthyApiClient healthyApiClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final List<Map<String, Object>> definitions;

    @Autowired
    public PatientReadToolRegistry(HealthyApiClient healthyApiClient, ObjectMapper objectMapper) {
        this(healthyApiClient, objectMapper, Clock.system(BUSINESS_ZONE));
    }

    PatientReadToolRegistry(
            HealthyApiClient healthyApiClient,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.healthyApiClient = healthyApiClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.definitions = buildDefinitions();
    }

    public List<Map<String, Object>> definitions() {
        return definitions;
    }

    public ToolExecutionResult execute(
            String name,
            String argumentsJson,
            String authorization,
            long userId
    ) {
        long startedAt = System.nanoTime();
        ToolExecutionResult result;
        try {
            ObjectNode arguments = parseArguments(argumentsJson);
            result = switch (name) {
                case "list_departments" -> noArguments(arguments,
                        () -> healthyApiClient.get("/api/user/departments", authorization));
                case "search_doctors" -> searchDoctors(arguments, authorization);
                case "get_doctor_detail" -> doctorDetail(arguments, authorization);
                case "list_schedule_slots" -> scheduleSlots(arguments, authorization);
                case "list_doctors_schedule_slots" -> doctorsScheduleSlots(
                        arguments, authorization);
                case "list_my_appointments" -> noArguments(arguments,
                        () -> healthyApiClient.get("/api/user/appointments", authorization));
                case "list_my_waitlists" -> noArguments(arguments,
                        () -> healthyApiClient.get("/api/user/waitlists", authorization));
                default -> invalid("模型选择了未注册的工具");
            };
        } catch (InvalidToolArguments exception) {
            result = invalid(exception.getMessage());
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        int code = result.ok() ? 0 : result.error().code();
        log.info("Patient read tool completed userId={} tool={} ok={} code={} elapsedMs={}",
                userId, safeToolName(name), result.ok(), code, elapsedMs);
        return result;
    }

    private ToolExecutionResult searchDoctors(ObjectNode arguments, String authorization) {
        requireAllowed(arguments, Set.of("departmentId", "keyword", "page", "pageSize"));
        Long departmentId = optionalPositiveLong(arguments, "departmentId");
        String keyword = optionalText(arguments, "keyword", 100);
        int page = optionalPositiveInt(arguments, "page", 1, Integer.MAX_VALUE, 1);
        int pageSize = optionalPositiveInt(
                arguments, "pageSize", 1, 100, DEFAULT_DOCTOR_PAGE_SIZE);

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("page", page);
        query.put("pageSize", pageSize);
        if (departmentId != null) query.put("departmentId", departmentId);
        if (keyword != null) query.put("keyword", keyword);
        return healthyApiClient.get("/api/user/doctors", query, authorization);
    }

    private ToolExecutionResult doctorDetail(ObjectNode arguments, String authorization) {
        requireAllowed(arguments, Set.of("doctorId"));
        long doctorId = requiredPositiveLong(arguments, "doctorId");
        return healthyApiClient.get("/api/user/doctors/" + doctorId, authorization);
    }

    private ToolExecutionResult scheduleSlots(ObjectNode arguments, String authorization) {
        requireAllowed(arguments, Set.of("doctorId", "startDate", "endDate"));
        long doctorId = requiredPositiveLong(arguments, "doctorId");
        LocalDate startDate = requiredDate(arguments, "startDate");
        LocalDate endDate = requiredDate(arguments, "endDate");
        validateScheduleRange(startDate, endDate);
        return healthyApiClient.get(
                "/api/user/doctors/" + doctorId + "/schedule-slots",
                Map.of("startDate", startDate, "endDate", endDate),
                authorization);
    }

    private void validateScheduleRange(LocalDate startDate, LocalDate endDate) {
        LocalDate today = LocalDate.now(clock);
        if (startDate.isBefore(today)) {
            throw new InvalidToolArguments("开始日期不能早于今天（" + today + "）");
        }
        if (endDate.isBefore(startDate)) {
            throw new InvalidToolArguments("结束日期不能早于开始日期");
        }
        if (ChronoUnit.DAYS.between(startDate, endDate) > 13) {
            throw new InvalidToolArguments("号源查询范围最多包含 14 天");
        }
    }

    private ToolExecutionResult doctorsScheduleSlots(
            ObjectNode arguments,
            String authorization
    ) {
        requireAllowed(arguments, Set.of(
                "doctorIds", "startDate", "endDate", "onlyAvailable"));
        List<Long> doctorIds = requiredPositiveLongs(
                arguments, "doctorIds", MAX_BATCH_DOCTORS);
        LocalDate startDate = requiredDate(arguments, "startDate");
        LocalDate endDate = requiredDate(arguments, "endDate");
        validateScheduleRange(startDate, endDate);
        boolean onlyAvailable = optionalBoolean(arguments, "onlyAvailable", true);

        var doctors = objectMapper.createArrayNode();
        var failures = objectMapper.createArrayNode();
        ToolError firstFailure = null;
        int successfulQueries = 0;
        int availableDoctorCount = 0;

        for (long doctorId : doctorIds) {
            ToolExecutionResult result = healthyApiClient.get(
                    "/api/user/doctors/" + doctorId + "/schedule-slots",
                    Map.of("startDate", startDate, "endDate", endDate),
                    authorization);
            if (!result.ok()) {
                ToolError error = result.error() == null
                        ? new ToolError(503, 50300, "DEPENDENCY_UNAVAILABLE",
                                "医院业务服务暂时不可用", true)
                        : result.error();
                if ("AUTH_REQUIRED".equals(error.type())
                        || "FORBIDDEN".equals(error.type())) {
                    return ToolExecutionResult.failure(error);
                }
                if (firstFailure == null) firstFailure = error;
                failures.add(batchFailure(doctorId, error));
                continue;
            }
            if (result.data() == null || !result.data().isArray()) {
                ToolError error = new ToolError(
                        503, 50300, "DEPENDENCY_UNAVAILABLE",
                        "医院业务服务返回了无法识别的号源数据", true);
                if (firstFailure == null) firstFailure = error;
                failures.add(batchFailure(doctorId, error));
                continue;
            }

            successfulQueries++;
            var slots = objectMapper.createArrayNode();
            for (JsonNode slot : result.data()) {
                if (!onlyAvailable || slot.path("remainingCapacity").asInt(0) > 0) {
                    slots.add(slot.deepCopy());
                }
            }
            if (!slots.isEmpty()) availableDoctorCount++;
            if (!onlyAvailable || !slots.isEmpty()) {
                ObjectNode doctor = objectMapper.createObjectNode();
                doctor.put("doctorId", doctorId);
                doctor.set("slots", slots);
                doctors.add(doctor);
            }
        }

        if (successfulQueries == 0 && firstFailure != null) {
            return ToolExecutionResult.failure(firstFailure);
        }

        ObjectNode data = objectMapper.createObjectNode();
        data.put("startDate", startDate.toString());
        data.put("endDate", endDate.toString());
        data.put("requestedDoctorCount", doctorIds.size());
        data.put("successfulDoctorCount", successfulQueries);
        data.put("availableDoctorCount", availableDoctorCount);
        data.put("partial", !failures.isEmpty());
        data.set("doctors", doctors);
        data.set("failures", failures);
        return ToolExecutionResult.success(data);
    }

    private ObjectNode batchFailure(long doctorId, ToolError error) {
        ObjectNode failure = objectMapper.createObjectNode();
        failure.put("doctorId", doctorId);
        failure.put("type", error == null ? "DEPENDENCY_UNAVAILABLE" : error.type());
        failure.put("message", error == null
                ? "医院业务服务暂时不可用" : error.message());
        failure.put("retryable", error != null && error.retryable());
        return failure;
    }

    private ToolExecutionResult noArguments(ObjectNode arguments, ToolSupplier supplier) {
        requireAllowed(arguments, Set.of());
        return supplier.get();
    }

    private ObjectNode parseArguments(String argumentsJson) {
        try {
            JsonNode parsed = objectMapper.readTree(
                    argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
            if (!(parsed instanceof ObjectNode objectNode)) {
                throw new InvalidToolArguments("工具参数必须是 JSON 对象");
            }
            return objectNode;
        } catch (JsonProcessingException exception) {
            throw new InvalidToolArguments("模型生成的工具参数不是有效 JSON");
        }
    }

    private void requireAllowed(ObjectNode arguments, Set<String> allowed) {
        List<String> unknown = new ArrayList<>();
        arguments.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) unknown.add(field);
        });
        if (!unknown.isEmpty()) {
            throw new InvalidToolArguments("工具参数包含未允许字段：" + String.join(", ", unknown));
        }
    }

    private long requiredPositiveLong(ObjectNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 1) {
            throw new InvalidToolArguments(field + " 必须是正整数");
        }
        return value.longValue();
    }

    private Long optionalPositiveLong(ObjectNode arguments, String field) {
        return arguments.has(field) ? requiredPositiveLong(arguments, field) : null;
    }

    private List<Long> requiredPositiveLongs(
            ObjectNode arguments,
            String field,
            int maxItems
    ) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isArray() || value.isEmpty()
                || value.size() > maxItems) {
            throw new InvalidToolArguments(
                    field + " 必须是包含 1 到 " + maxItems + " 个医生 ID 的数组");
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isIntegralNumber() || !item.canConvertToLong()
                    || item.longValue() < 1) {
                throw new InvalidToolArguments(field + " 中的医生 ID 必须是正整数");
            }
            unique.add(item.longValue());
        }
        return List.copyOf(unique);
    }

    private boolean optionalBoolean(
            ObjectNode arguments,
            String field,
            boolean defaultValue
    ) {
        if (!arguments.has(field)) return defaultValue;
        JsonNode value = arguments.get(field);
        if (!value.isBoolean()) {
            throw new InvalidToolArguments(field + " 必须是布尔值");
        }
        return value.booleanValue();
    }

    private int optionalPositiveInt(
            ObjectNode arguments,
            String field,
            int min,
            int max,
            int defaultValue
    ) {
        if (!arguments.has(field)) return defaultValue;
        JsonNode value = arguments.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < min || value.intValue() > max) {
            throw new InvalidToolArguments(field + " 必须在 " + min + " 到 " + max + " 之间");
        }
        return value.intValue();
    }

    private String optionalText(ObjectNode arguments, String field, int maxLength) {
        if (!arguments.has(field)) return null;
        JsonNode value = arguments.get(field);
        if (!value.isTextual()) throw new InvalidToolArguments(field + " 必须是字符串");
        String text = value.textValue().strip();
        if (text.isEmpty() || text.length() > maxLength) {
            throw new InvalidToolArguments(field + " 长度无效");
        }
        return text;
    }

    private LocalDate requiredDate(ObjectNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isTextual()) {
            throw new InvalidToolArguments(field + " 必须是 YYYY-MM-DD 日期");
        }
        try {
            return LocalDate.parse(value.textValue());
        } catch (java.time.format.DateTimeParseException exception) {
            throw new InvalidToolArguments(field + " 必须是有效的 YYYY-MM-DD 日期");
        }
    }

    private ToolExecutionResult invalid(String message) {
        return ToolExecutionResult.failure(new ToolError(
                400, 40001, "INVALID_ARGUMENT", message, false));
    }

    private String safeToolName(String name) {
        return name == null ? "<null>" : name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private List<Map<String, Object>> buildDefinitions() {
        Map<String, Object> emptyParameters = parameters(Map.of(), List.of());
        return List.of(
                tool("list_departments", "查询患者可以选择的已启用科室。成功结果会保存为可后续引用的科室 ResultSet。", emptyParameters),
                tool("search_doctors", "查询患者可见的医生。不要生成 departmentId；可使用科室名称或关键词。成功结果会保存为医生 ResultSet。全院号源查询应先用本工具取得医生 ResultSet，再把 resultSetId 传给批量号源工具。",
                        parameters(Map.of(
                                "departmentName", string("科室名称；服务端会从可信科室 ResultSet 解析，或作为关键词查询"),
                                "keyword", string("匹配医生姓名、科室、职称或简介"),
                                "page", integer("页码，默认 1", 1, null),
                                "pageSize", integer("每页数量，批量查号源时最多使用 30", 1, 100)
                        ), List.of())),
                tool("get_doctor_detail", "从最近医生 ResultSet 中按 resultSetId、序号或医生名称确定真实医生，再查询详情。禁止传 doctorId。",
                        parameters(selectorProperties(), List.of())),
                tool("list_schedule_slots", "从最近业务 ResultSet 中按 resultSetId、序号或医生名称确定真实医生，再查询其日期范围内的号源。禁止传 doctorId。成功结果会保存为号源 ResultSet。",
                        parameters(withSelectorProperties(Map.of(
                                "startDate", date("开始日期，Asia/Shanghai 时区，不能早于今天"),
                                "endDate", date("结束日期，范围包含首尾最多 14 天")
                        )), List.of("startDate", "endDate"))),
                tool("list_doctors_schedule_slots", "批量查询一个医生 ResultSet 中所有医生的号源。必须传 search_doctors 返回的 resultSetId，服务端从 State 解析真实 doctorId；最多 30 位医生。成功结果会保存为号源 ResultSet。",
                        parameters(Map.of(
                                "doctorResultSetId", string("search_doctors 返回的医生 ResultSet ID，不是业务数据库 ID"),
                                "startDate", date("开始日期，Asia/Shanghai 时区，不能早于今天"),
                                "endDate", date("结束日期，范围包含首尾最多 14 天"),
                                "onlyAvailable", bool("是否只返回有余号的班次，默认 true")
                        ), List.of("doctorResultSetId", "startDate", "endDate"))),
                tool("list_my_appointments", "查询当前患者全部预约并保存为预约 ResultSet。需要取消时先查询，再调用 select_patient_candidate 按日期、医生、科室、时段或序号选择；禁止生成 appointmentId。", emptyParameters),
                tool("list_my_waitlists", "查询当前患者候补记录和服务端提供的名额截止时间，并保存为候补 ResultSet。取消候补只能选择 WAITING，确认名额只能选择 OFFERED；禁止生成 waitlistId。", emptyParameters)
        );
    }

    private Map<String, Object> selectorProperties() {
        return Map.of(
                "resultSetId", string("要引用的 ResultSet ID；省略时使用 currentResultSet"),
                "position", integer("结果集中的一基序号", 1, null),
                "candidateType", string("候选类型，例如 DOCTOR"),
                "doctorName", string("医生姓名"),
                "departmentName", string("科室名称"),
                "date", date("日期"),
                "sessionName", string("时段，例如上午或下午"),
                "status", string("业务状态")
        );
    }

    private Map<String, Object> withSelectorProperties(Map<String, Object> additional) {
        Map<String, Object> merged = new LinkedHashMap<>(selectorProperties());
        merged.putAll(additional);
        return Map.copyOf(merged);
    }

    private Map<String, Object> tool(String name, String description, Map<String, Object> parameters) {
        return Map.of("type", "function", "function", Map.of(
                "name", name,
                "description", description,
                "parameters", parameters
        ));
    }

    private Map<String, Object> parameters(
            Map<String, Object> properties,
            List<String> required
    ) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    private Map<String, Object> integer(String description, int minimum, Integer maximum) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        schema.put("minimum", minimum);
        if (maximum != null) schema.put("maximum", maximum);
        schema.put("description", description);
        return Map.copyOf(schema);
    }

    private Map<String, Object> string(String description) {
        return Map.of("type", "string", "minLength", 1, "description", description);
    }

    private Map<String, Object> date(String description) {
        return Map.of("type", "string", "format", "date", "description", description);
    }

    private Map<String, Object> longArray(
            String description,
            int minItems,
            int maxItems
    ) {
        return Map.of(
                "type", "array",
                "items", Map.of("type", "integer", "minimum", 1),
                "minItems", minItems,
                "maxItems", maxItems,
                "uniqueItems", true,
                "description", description
        );
    }

    private Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    @FunctionalInterface
    private interface ToolSupplier {
        ToolExecutionResult get();
    }

    private static final class InvalidToolArguments extends RuntimeException {
        private InvalidToolArguments(String message) {
            super(message);
        }
    }
}
