package com.healthy.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healthy.agent.chat.PatientToolExecutionContext;
import com.healthy.agent.state.AgentState;
import com.healthy.agent.state.AgentStateService;
import com.healthy.agent.state.Candidate;
import com.healthy.agent.state.CandidateResultSet;
import com.healthy.agent.state.CandidateSelectionResult;
import com.healthy.agent.state.CandidateSelector;
import com.healthy.agent.state.CandidateType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Converts model-facing references into trusted IDs held by AgentState. */
@Component
public class PatientToolStateAdapter {
    private static final int MAX_BATCH_DOCTORS = 30;

    private final AgentStateService stateService;
    private final ObjectMapper objectMapper;

    public PatientToolStateAdapter(AgentStateService stateService, ObjectMapper objectMapper) {
        this.stateService = stateService;
        this.objectMapper = objectMapper;
    }

    public NormalizedToolInput normalize(
            String toolName,
            String input,
            PatientToolExecutionContext context
    ) {
        try {
            ObjectNode arguments = object(input);
            return switch (toolName) {
                case "list_departments", "list_my_appointments", "list_my_waitlists" ->
                        noArguments(arguments);
                case "search_doctors" -> searchDoctors(arguments, context);
                case "get_doctor_detail" -> doctorDetail(arguments, context);
                case "list_schedule_slots" -> scheduleSlots(arguments, context);
                case "list_doctors_schedule_slots" -> doctorsScheduleSlots(arguments, context);
                default -> NormalizedToolInput.ready(input == null ? "{}" : input);
            };
        } catch (InvalidReference exception) {
            return NormalizedToolInput.failed(exception.getMessage());
        }
    }

    public CandidateSelector selector(String input) {
        try {
            ObjectNode arguments = object(input);
            requireAllowed(arguments, Set.of(
                    "resultSetId", "position", "candidateType", "doctorName",
                    "departmentName", "date", "sessionName", "status"));
            Integer position = optionalPositiveInt(arguments, "position");
            CandidateType type = optionalCandidateType(arguments, "candidateType");
            return new CandidateSelector(
                    optionalText(arguments, "resultSetId"), position, type,
                    optionalText(arguments, "doctorName"),
                    optionalText(arguments, "departmentName"),
                    optionalText(arguments, "date"),
                    optionalText(arguments, "sessionName"),
                    optionalText(arguments, "status"));
        } catch (InvalidReference exception) {
            throw exception;
        }
    }

    private NormalizedToolInput searchDoctors(
            ObjectNode arguments,
            PatientToolExecutionContext context
    ) {
        requireAllowed(arguments, Set.of("departmentName", "keyword", "page", "pageSize"));
        ObjectNode normalized = objectMapper.createObjectNode();
        copy(arguments, normalized, "keyword", "page", "pageSize");
        String departmentName = optionalText(arguments, "departmentName");
        if (departmentName != null) {
            List<Candidate> departments = recentCandidates(
                    context.conversationId(), CandidateType.DEPARTMENT).stream()
                    .filter(candidate -> contains(candidate.departmentName(), departmentName)
                            || contains(candidate.displayText(), departmentName))
                    .toList();
            if (departments.size() == 1) {
                normalized.put("departmentId", departments.getFirst().businessId());
            } else if (!normalized.has("keyword")) {
                normalized.put("keyword", departmentName);
            }
        }
        return NormalizedToolInput.ready(json(normalized));
    }

    private NormalizedToolInput doctorDetail(
            ObjectNode arguments,
            PatientToolExecutionContext context
    ) {
        Candidate candidate = resolveDoctor(arguments, context);
        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.put("doctorId", doctorId(candidate));
        return NormalizedToolInput.ready(json(normalized));
    }

    private NormalizedToolInput scheduleSlots(
            ObjectNode arguments,
            PatientToolExecutionContext context
    ) {
        requireAllowed(arguments, selectorFields("startDate", "endDate"));
        Candidate candidate = resolveDoctor(arguments, context);
        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.put("doctorId", doctorId(candidate));
        copyRequiredText(arguments, normalized, "startDate", "endDate");
        return NormalizedToolInput.ready(json(normalized));
    }

    private NormalizedToolInput doctorsScheduleSlots(
            ObjectNode arguments,
            PatientToolExecutionContext context
    ) {
        requireAllowed(arguments, Set.of(
                "doctorResultSetId", "startDate", "endDate", "onlyAvailable"));
        String requestedId = optionalText(arguments, "doctorResultSetId");
        CandidateResultSet source = requestedId == null
                ? stateService.getCurrentResultSet(context.conversationId()).orElse(null)
                : stateService.findRecentResultSet(context.conversationId(), requestedId).orElse(null);
        if (source == null) {
            throw new InvalidReference("没有找到医生查询结果，请先查询医生。");
        }
        LinkedHashSet<Long> doctorIds = new LinkedHashSet<>();
        for (Candidate candidate : source.candidates()) {
            Long doctorId = candidate.type() == CandidateType.DOCTOR
                    ? candidate.businessId() : candidate.doctorId();
            if (doctorId != null && doctorId > 0) doctorIds.add(doctorId);
        }
        if (doctorIds.isEmpty()) {
            throw new InvalidReference("该结果集中没有可查询号源的医生。");
        }
        if (doctorIds.size() > MAX_BATCH_DOCTORS) {
            throw new InvalidReference("医生候选超过 30 位，请先按科室或姓名缩小范围。");
        }
        ObjectNode normalized = objectMapper.createObjectNode();
        var ids = normalized.putArray("doctorIds");
        doctorIds.forEach(ids::add);
        copyRequiredText(arguments, normalized, "startDate", "endDate");
        if (arguments.has("onlyAvailable")) {
            if (!arguments.get("onlyAvailable").isBoolean()) {
                throw new InvalidReference("onlyAvailable 必须是布尔值");
            }
            normalized.put("onlyAvailable", arguments.get("onlyAvailable").booleanValue());
        }
        return NormalizedToolInput.ready(json(normalized));
    }

    private Candidate resolveDoctor(
            ObjectNode arguments,
            PatientToolExecutionContext context
    ) {
        CandidateSelector selector = selector(json(selectionArguments(arguments)));
        CandidateSelectionResult selection = stateService.selectCandidate(
                context.conversationId(), selector);
        if (selection.status() != CandidateSelectionResult.Status.SELECTED) {
            throw new InvalidReference(selection.message());
        }
        Candidate candidate = selection.selected();
        if (candidate.type() != CandidateType.DOCTOR && candidate.doctorId() == null) {
            throw new InvalidReference("所选项目不是医生，也不包含可信医生引用。");
        }
        return candidate;
    }

    private long doctorId(Candidate candidate) {
        return candidate.type() == CandidateType.DOCTOR
                ? candidate.businessId() : candidate.doctorId();
    }

    private List<Candidate> recentCandidates(String conversationId, CandidateType type) {
        AgentState state = stateService.getOrCreate(conversationId);
        List<Candidate> candidates = new ArrayList<>();
        for (CandidateResultSet resultSet : state.recentResultSets()) {
            for (Candidate candidate : resultSet.candidates()) {
                if (candidate.type() == type) candidates.add(candidate);
            }
        }
        return candidates;
    }

    private ObjectNode selectionArguments(ObjectNode source) {
        ObjectNode selected = objectMapper.createObjectNode();
        copy(source, selected, "resultSetId", "position", "candidateType",
                "doctorName", "departmentName", "date", "sessionName", "status");
        selected.remove("candidateType");
        return selected;
    }

    private Set<String> selectorFields(String... additional) {
        Set<String> fields = new LinkedHashSet<>(List.of(
                "resultSetId", "position", "candidateType", "doctorName",
                "departmentName", "date", "sessionName", "status"));
        fields.addAll(List.of(additional));
        return fields;
    }

    private NormalizedToolInput noArguments(ObjectNode arguments) {
        requireAllowed(arguments, Set.of());
        return NormalizedToolInput.ready("{}");
    }

    private ObjectNode object(String input) {
        try {
            JsonNode parsed = objectMapper.readTree(input == null || input.isBlank() ? "{}" : input);
            if (parsed instanceof ObjectNode objectNode) return objectNode;
            throw new InvalidReference("工具参数必须是 JSON 对象");
        } catch (JsonProcessingException exception) {
            throw new InvalidReference("工具参数不是有效 JSON");
        }
    }

    private void requireAllowed(ObjectNode arguments, Set<String> allowed) {
        List<String> unknown = new ArrayList<>();
        arguments.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) unknown.add(field);
        });
        if (!unknown.isEmpty()) {
            throw new InvalidReference("工具参数包含未允许字段：" + String.join(", ", unknown));
        }
    }

    private void copy(ObjectNode source, ObjectNode target, String... fields) {
        for (String field : fields) {
            if (source.has(field)) target.set(field, source.get(field));
        }
    }

    private void copyRequiredText(ObjectNode source, ObjectNode target, String... fields) {
        for (String field : fields) {
            String value = optionalText(source, field);
            if (value == null) throw new InvalidReference(field + " 为必填项");
            target.put(field, value);
        }
    }

    private String optionalText(ObjectNode arguments, String field) {
        if (!arguments.has(field)) return null;
        JsonNode value = arguments.get(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new InvalidReference(field + " 必须是非空字符串");
        }
        return value.textValue().strip();
    }

    private Integer optionalPositiveInt(ObjectNode arguments, String field) {
        if (!arguments.has(field)) return null;
        JsonNode value = arguments.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
            throw new InvalidReference(field + " 必须是正整数");
        }
        return value.intValue();
    }

    private CandidateType optionalCandidateType(ObjectNode arguments, String field) {
        String value = optionalText(arguments, field);
        if (value == null) return null;
        try {
            return CandidateType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new InvalidReference("candidateType 无效");
        }
    }

    private boolean contains(String actual, String expected) {
        return actual != null && actual.toLowerCase(Locale.ROOT)
                .contains(expected.toLowerCase(Locale.ROOT));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record NormalizedToolInput(boolean ok, String json, String error) {
        static NormalizedToolInput ready(String json) {
            return new NormalizedToolInput(true, json, null);
        }

        static NormalizedToolInput failed(String message) {
            return new NormalizedToolInput(false, null, message);
        }
    }

    private static final class InvalidReference extends RuntimeException {
        private InvalidReference(String message) {
            super(message);
        }
    }
}
