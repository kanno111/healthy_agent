package com.healthy.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthy.agent.state.AgentStateService;
import com.healthy.agent.state.Candidate;
import com.healthy.agent.state.CandidateResultSet;
import com.healthy.agent.state.CandidateType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PatientResultSetFactory {
    private final AgentStateService stateService;
    private final ObjectMapper objectMapper;

    public PatientResultSetFactory(AgentStateService stateService, ObjectMapper objectMapper) {
        this.stateService = stateService;
        this.objectMapper = objectMapper;
    }

    public CandidateResultSet create(
            String conversationId,
            String toolName,
            String normalizedInput,
            JsonNode data
    ) {
        return create(conversationId, toolName, normalizedInput, data, null);
    }

    public CandidateResultSet create(
            String conversationId,
            String toolName,
            String normalizedInput,
            JsonNode data,
            String userQuery
    ) {
        ResultSeed seed = switch (toolName) {
            case "list_departments" -> departments(data);
            case "search_doctors" -> doctors(data, "医生查询结果");
            case "get_doctor_detail" -> doctorDetail(data);
            case "list_schedule_slots" -> slots(
                    conversationId, arguments(normalizedInput), data);
            case "list_doctors_schedule_slots" -> batchSlots(
                    conversationId, arguments(normalizedInput), data);
            case "list_my_appointments" -> appointments(data);
            case "list_my_waitlists" -> waitlists(data);
            default -> null;
        };
        if (seed == null) return null;
        return stateService.addResultSet(
                conversationId, contextualDescription(seed.description(), userQuery),
                seed.type(), seed.candidates());
    }

    public String format(CandidateResultSet resultSet) {
        if (resultSet.candidates().isEmpty()) {
            return "“" + resultSet.description() + "”没有找到记录。";
        }
        StringBuilder answer = new StringBuilder(resultSet.description()).append("：\n\n");
        for (int index = 0; index < resultSet.candidates().size(); index++) {
            answer.append(index + 1).append(". ")
                    .append(resultSet.candidates().get(index).displayText()).append('\n');
        }
        answer.append("\n你可以按序号、医生、科室、日期或时段继续选择。当前列表编号只对应这份查询结果。");
        return answer.toString();
    }

    public Map<String, Object> referenceView(CandidateResultSet resultSet) {
        List<Map<String, Object>> choices = new ArrayList<>();
        for (int i = 0; i < resultSet.candidates().size(); i++) {
            choices.add(Map.of(
                    "position", i + 1,
                    "type", resultSet.candidates().get(i).type().name(),
                    "displayText", resultSet.candidates().get(i).displayText()));
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("resultSetId", resultSet.resultSetId());
        view.put("description", resultSet.description());
        view.put("type", resultSet.type().name());
        view.put("choices", choices);
        return view;
    }

    private ResultSeed departments(JsonNode data) {
        List<Candidate> result = new ArrayList<>();
        if (data != null && data.isArray()) {
            for (JsonNode item : data) {
                long id = positiveId(item, "id");
                if (id < 1) continue;
                String name = text(item, "name");
                result.add(candidate(id, CandidateType.DEPARTMENT,
                        safe(name), null, null, id, name,
                        null, null, null, null, null));
            }
        }
        return new ResultSeed("当前可选科室", CandidateType.DEPARTMENT, result);
    }

    private ResultSeed doctors(JsonNode data, String description) {
        JsonNode records = data == null ? null : data.path("records");
        if (records == null || !records.isArray()) records = data;
        List<Candidate> result = new ArrayList<>();
        if (records != null && records.isArray()) {
            for (JsonNode item : records) {
                Candidate doctor = doctorCandidate(item);
                if (doctor != null) result.add(doctor);
            }
        }
        return new ResultSeed(description, CandidateType.DOCTOR, result);
    }

    private ResultSeed doctorDetail(JsonNode data) {
        Candidate doctor = doctorCandidate(data);
        return new ResultSeed("医生详情", CandidateType.DOCTOR,
                doctor == null ? List.of() : List.of(doctor));
    }

    private Candidate doctorCandidate(JsonNode item) {
        if (item == null || !item.isObject()) return null;
        long id = positiveId(item, "id");
        if (id < 1) return null;
        String name = text(item, "name");
        String department = text(item, "departmentName");
        String title = text(item, "title");
        String display = join(name, department, title);
        if (item.has("hasAvailableSlots")) {
            display += item.path("hasAvailableSlots").asBoolean(false) ? " 有可预约号源" : " 暂无可预约号源";
        }
        return candidate(id, CandidateType.DOCTOR, display, id, name,
                positiveObjectId(item, "departmentId"), department,
                null, null, null, null, null);
    }

    private ResultSeed slots(String conversationId, JsonNode arguments, JsonNode data) {
        long doctorId = positiveId(arguments, "doctorId");
        Candidate doctor = stateService.findCandidate(
                conversationId, CandidateType.DOCTOR, doctorId).orElse(null);
        String doctorName = doctor == null ? null : doctor.doctorName();
        String departmentName = doctor == null ? null : doctor.departmentName();
        List<Candidate> result = slotCandidates(
                doctorId, doctorName, departmentName, data);
        String description = join(doctorName == null ? "所选医生" : doctorName,
                text(arguments, "startDate") + "至" + text(arguments, "endDate"), "号源");
        return new ResultSeed(description, CandidateType.SCHEDULE_SLOT, result);
    }

    private ResultSeed batchSlots(String conversationId, JsonNode arguments, JsonNode data) {
        List<Candidate> result = new ArrayList<>();
        JsonNode doctors = data == null ? null : data.path("doctors");
        if (doctors != null && doctors.isArray()) {
            for (JsonNode doctorNode : doctors) {
                long doctorId = positiveId(doctorNode, "doctorId");
                Candidate doctor = stateService.findCandidate(
                        conversationId, CandidateType.DOCTOR, doctorId).orElse(null);
                result.addAll(slotCandidates(
                        doctorId,
                        doctor == null ? null : doctor.doctorName(),
                        doctor == null ? null : doctor.departmentName(),
                        doctorNode.path("slots")));
            }
        }
        String description = text(arguments, "startDate") + "至"
                + text(arguments, "endDate") + "可预约号源";
        return new ResultSeed(description, CandidateType.SCHEDULE_SLOT, result);
    }

    private List<Candidate> slotCandidates(
            long doctorId,
            String doctorName,
            String departmentName,
            JsonNode slots
    ) {
        List<Candidate> result = new ArrayList<>();
        if (slots == null || !slots.isArray()) return result;
        for (JsonNode slot : slots) {
            long id = positiveId(slot, "id");
            if (id < 1) continue;
            String date = text(slot, "scheduleDate");
            String sessionType = text(slot, "sessionType");
            String sessionName = text(slot, "sessionName");
            int capacity = slot.path("remainingCapacity").asInt(0);
            String display = join(doctorName, departmentName, date, sessionName,
                    timeRange(slot), "余号 " + capacity);
            result.add(candidate(id, CandidateType.SCHEDULE_SLOT, display,
                    doctorId > 0 ? doctorId : null, doctorName, null, departmentName,
                    date, sessionType, sessionName, null, capacity));
        }
        return result;
    }

    private ResultSeed appointments(JsonNode data) {
        List<Candidate> result = new ArrayList<>();
        if (data != null && data.isArray()) {
            for (JsonNode item : data) {
                long id = positiveId(item, "id");
                if (id < 1) continue;
                String status = text(item, "status");
                String display = "[" + appointmentStatus(status) + "] " + join(
                        text(item, "doctorName"), text(item, "departmentName"),
                        text(item, "scheduleDate"), text(item, "sessionName"),
                        timeRange(item));
                result.add(candidate(id, CandidateType.APPOINTMENT, display,
                        positiveObjectId(item, "doctorId"), text(item, "doctorName"),
                        positiveObjectId(item, "departmentId"), text(item, "departmentName"),
                        text(item, "scheduleDate"), null, text(item, "sessionName"),
                        status, null));
            }
        }
        result.sort(java.util.Comparator.comparingInt(candidate ->
                "BOOKED".equals(candidate.status()) ? 0 : 1));
        return new ResultSeed("我的预约", CandidateType.APPOINTMENT, result);
    }

    private ResultSeed waitlists(JsonNode data) {
        List<Candidate> result = new ArrayList<>();
        if (data != null && data.isArray()) {
            for (JsonNode item : data) {
                long id = positiveId(item, "id");
                if (id < 1) continue;
                String status = text(item, "status");
                String display = join(
                        "[" + waitlistStatus(status) + "]",
                        text(item, "doctorName"), text(item, "departmentName"),
                        text(item, "scheduleDate"), text(item, "sessionName"),
                        timeRange(item), offerExpiryText(item));
                result.add(candidate(id, CandidateType.WAITLIST, display,
                        positiveObjectId(item, "doctorId"), text(item, "doctorName"),
                        positiveObjectId(item, "departmentId"), text(item, "departmentName"),
                        text(item, "scheduleDate"), null, text(item, "sessionName"),
                        status, null));
            }
        }
        result.sort(java.util.Comparator.comparingInt(candidate ->
                waitlistStatusOrder(candidate.status())));
        return new ResultSeed("我的候补", CandidateType.WAITLIST, result);
    }

    private Candidate candidate(
            long id,
            CandidateType type,
            String display,
            Long doctorId,
            String doctorName,
            Long departmentId,
            String departmentName,
            String date,
            String sessionType,
            String sessionName,
            String status,
            Integer remainingCapacity
    ) {
        return new Candidate(id, type, display, doctorId, doctorName,
                departmentId, departmentName, date, sessionType, sessionName,
                status, remainingCapacity);
    }

    private JsonNode arguments(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (JsonProcessingException exception) {
            return objectMapper.createObjectNode();
        }
    }

    private long positiveId(JsonNode node, String field) {
        if (node == null) return -1;
        JsonNode value = node.get(field);
        return value != null && value.canConvertToLong() && value.longValue() > 0
                ? value.longValue() : -1;
    }

    private Long positiveObjectId(JsonNode node, String field) {
        long value = positiveId(node, field);
        return value > 0 ? value : null;
    }

    private String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String timeRange(JsonNode node) {
        String start = shortTime(text(node, "startTime"));
        String end = shortTime(text(node, "endTime"));
        return start == null && end == null ? null : safe(start) + "-" + safe(end);
    }

    private String shortTime(String value) {
        return value == null || value.isBlank() ? null
                : value.length() >= 5 ? value.substring(0, 5) : value;
    }

    private String appointmentStatus(String status) {
        return switch (status == null ? "" : status) {
            case "BOOKED" -> "有效预约";
            case "CANCELLED" -> "已取消";
            case "COMPLETED" -> "已完成";
            default -> safe(status);
        };
    }

    private String waitlistStatus(String status) {
        return switch (status == null ? "" : status) {
            case "WAITING" -> "排队中";
            case "OFFERED" -> "待确认名额";
            case "CONFIRMED" -> "已确认";
            case "EXPIRED" -> "已过期";
            case "CANCELLED" -> "已取消";
            default -> safe(status);
        };
    }

    private int waitlistStatusOrder(String status) {
        if ("OFFERED".equals(status)) return 0;
        if ("WAITING".equals(status)) return 1;
        return 2;
    }

    private String offerExpiryText(JsonNode item) {
        String expiry = text(item, "offerExpireTime");
        if (expiry == null || expiry.isBlank()) return null;
        return "确认截止 " + expiry.replace('T', ' ');
    }

    private String join(String... values) {
        return java.util.Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "未提供" : value;
    }

    private String contextualDescription(String base, String userQuery) {
        if (userQuery == null || userQuery.isBlank()) return base;
        String compact = userQuery.replace('\n', ' ').replace('\r', ' ').strip();
        if (compact.length() > 60) compact = compact.substring(0, 60) + "…";
        return base + "（对应用户查询：“" + compact + "”）";
    }

    private record ResultSeed(
            String description,
            CandidateType type,
            List<Candidate> candidates
    ) {
    }
}
