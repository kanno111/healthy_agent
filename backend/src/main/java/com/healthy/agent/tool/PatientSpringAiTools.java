package com.healthy.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthy.agent.action.ActionPreparationResult;
import com.healthy.agent.action.PatientActionService;
import com.healthy.agent.chat.PatientToolExecutionContext;
import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.knowledge.rag.KnowledgeRagRequest;
import com.healthy.agent.knowledge.rag.KnowledgeRagResponse;
import com.healthy.agent.knowledge.rag.KnowledgeRagService;
import com.healthy.agent.state.AgentStateService;
import com.healthy.agent.state.Candidate;
import com.healthy.agent.state.CandidateResultSet;
import com.healthy.agent.state.CandidateSelectionResult;
import com.healthy.agent.state.CandidateSelector;
import com.healthy.agent.state.CandidateType;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PatientSpringAiTools {
    public static final String RAG_TOOL = "search_hospital_policy";
    public static final String SELECT_CANDIDATE = "select_patient_candidate";
    private static final int PATIENT_RAG_TOP_K = 3;
    private static final String TERMINAL_ACTION_RETRY_RULE =
            " 如果旧操作已经是 REJECTED、EXPIRED、FAILED 或 SUCCEEDED，旧确认卡永久不可再次确认；"
                    + "当 AgentState.pendingAction=none 且用户再次明确请求时，必须调用本工具生成具有新 actionId 的新确认卡，不得让用户点击旧卡。";

    private final PatientReadToolRegistry readToolRegistry;
    private final PatientActionService actionService;
    private final KnowledgeRagService ragService;
    private final AgentStateService stateService;
    private final PatientToolStateAdapter stateAdapter;
    private final PatientResultSetFactory resultSetFactory;
    private final ObjectMapper objectMapper;
    private final List<ToolCallback> callbacks;

    public PatientSpringAiTools(
            PatientReadToolRegistry readToolRegistry,
            PatientActionService actionService,
            KnowledgeRagService ragService,
            AgentStateService stateService,
            PatientToolStateAdapter stateAdapter,
            PatientResultSetFactory resultSetFactory,
            ObjectMapper objectMapper
    ) {
        this.readToolRegistry = readToolRegistry;
        this.actionService = actionService;
        this.ragService = ragService;
        this.stateService = stateService;
        this.stateAdapter = stateAdapter;
        this.resultSetFactory = resultSetFactory;
        this.objectMapper = objectMapper;
        this.callbacks = buildCallbacks();
    }

    public List<ToolCallback> callbacks() {
        return callbacks;
    }

    private List<ToolCallback> buildCallbacks() {
        List<ToolCallback> result = new ArrayList<>();
        for (Map<String, Object> definition : readToolRegistry.definitions()) {
            result.add(readToolCallback(definition));
        }
        result.add(selectionCallback());
        result.add(ragCallback());
        result.add(createPreparationCallback());
        result.add(cancelPreparationCallback());
        result.add(joinWaitlistPreparationCallback());
        result.add(cancelWaitlistPreparationCallback());
        result.add(confirmWaitlistPreparationCallback());
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private ToolCallback readToolCallback(Map<String, Object> definition) {
        Map<String, Object> function = (Map<String, Object>) definition.get("function");
        String name = (String) function.get("name");
        return callback(
                name,
                (String) function.get("description"),
                json(function.get("parameters")),
                false,
                (input, toolContext) -> {
                    PatientToolExecutionContext context = state(toolContext);
                    context.recordTool(name);
                    PatientToolStateAdapter.NormalizedToolInput normalized =
                            stateAdapter.normalize(name, input, context);
                    if (!normalized.ok()) {
                        context.directAnswer(normalized.error());
                        return normalized.error();
                    }
                    ToolExecutionResult execution = readToolRegistry.execute(
                            name, normalized.json(), context.authorization(), context.userId());
                    propagateAuthenticationFailure(execution, context);
                    CandidateResultSet resultSet = null;
                    if (execution.ok()) {
                        resultSet = resultSetFactory.create(
                                context.conversationId(), name, normalized.json(), execution.data(),
                                context.question());
                    }
                    if (resultSet != null) {
                        context.lastResultSet(resultSet);
                        context.directAnswer(resultSetFactory.format(resultSet));
                    }
                    return toolResult(execution, resultSet);
                });
    }

    private ToolCallback selectionCallback() {
        Map<String, Object> schema = parameters(Map.of(
                "resultSetId", text("要引用的 ResultSet ID；省略时使用 currentResultSet"),
                "position", integer("结果集中的一基序号"),
                "candidateType", text("DEPARTMENT、DOCTOR、SCHEDULE_SLOT、APPOINTMENT 或 WAITLIST"),
                "doctorName", text("医生姓名筛选"),
                "departmentName", text("科室名称筛选"),
                "date", text("YYYY-MM-DD 日期筛选"),
                "sessionName", text("时段筛选，例如上午或下午"),
                "status", text("状态筛选；取消预约应使用 BOOKED")
        ), List.of());
        return callback(
                SELECT_CANDIDATE,
                "把用户的自然语言选择确定性映射到 AgentState 中的真实业务对象。只传 ResultSet 引用、序号或业务筛选条件，严禁传 doctorId、scheduleSlotId、appointmentId 等数据库 ID。零条时说明未找到，多条时返回候选让用户继续选，唯一时设置 selectedCandidate。",
                json(schema),
                false,
                (input, toolContext) -> {
                    PatientToolExecutionContext context = state(toolContext);
                    context.recordTool(SELECT_CANDIDATE);
                    CandidateSelector selector;
                    try {
                        selector = stateAdapter.selector(input);
                    } catch (RuntimeException exception) {
                        context.directAnswer(exception.getMessage());
                        return exception.getMessage();
                    }
                    CandidateSelectionResult selection = stateService.selectCandidate(
                            context.conversationId(), selector);
                    String answer = formatSelection(selection);
                    context.directAnswer(answer);
                    return json(selectionView(selection));
                });
    }

    private ToolCallback createPreparationCallback() {
        return callback(
                PatientActionService.PREPARE_CREATE_APPOINTMENT,
                "为 selectedCandidate 中已经由 Java 唯一确定的 SCHEDULE_SLOT 生成预约确认卡。本工具没有业务 ID 参数；如果尚未选择，先调用 select_patient_candidate。模型不得自行挑选号源。只有患者点击确认卡按钮后才真正创建预约。"
                        + TERMINAL_ACTION_RETRY_RULE,
                json(parameters(Map.of(), List.of())),
                true,
                (input, toolContext) -> {
                    PatientToolExecutionContext context = state(toolContext);
                    context.recordTool(PatientActionService.PREPARE_CREATE_APPOINTMENT);
                    if (!emptyObject(input)) return direct(context, "该工具不接受参数，请先通过 ResultSet 选择号源。");
                    Candidate candidate = stateService.selectedCandidate(
                            context.conversationId()).orElse(null);
                    if (candidate == null || candidate.type() != CandidateType.SCHEDULE_SLOT) {
                        return direct(context, "尚未确定唯一号源，请先调用 select_patient_candidate 选择号源。");
                    }
                    if (candidate.remainingCapacity() != null && candidate.remainingCapacity() < 1) {
                        return direct(context, "所选班次快照中已无余号，请重新查询号源。");
                    }
                    ActionPreparationResult preparation = actionService.prepareCreation(
                            context.conversationId(), candidate,
                            context.authorization(), context.userId());
                    return handlePreparation(context, preparation);
                });
    }

    private ToolCallback cancelPreparationCallback() {
        return callback(
                PatientActionService.PREPARE_CANCEL_APPOINTMENT,
                "为 selectedCandidate 中已经由 Java 唯一确定的 BOOKED 预约生成取消确认卡。本工具没有业务 ID 参数；如果尚未选择，先调用 list_my_appointments，再调用 select_patient_candidate 按日期、医生、科室、时段或序号选择。模型不得生成 appointmentId。"
                        + TERMINAL_ACTION_RETRY_RULE,
                json(parameters(Map.of(), List.of())),
                true,
                (input, toolContext) -> {
                    PatientToolExecutionContext context = state(toolContext);
                    context.recordTool(PatientActionService.PREPARE_CANCEL_APPOINTMENT);
                    if (!emptyObject(input)) return direct(context, "该工具不接受参数，请先通过 ResultSet 选择预约。");
                    Candidate candidate = stateService.selectedCandidate(
                            context.conversationId()).orElse(null);
                    if (candidate == null || candidate.type() != CandidateType.APPOINTMENT) {
                        return direct(context, "尚未确定唯一预约，请先查询并调用 select_patient_candidate 选择预约。");
                    }
                    if (!"BOOKED".equals(candidate.status())) {
                        return direct(context, "所选预约不是可取消的有效预约，请重新选择 BOOKED 预约。");
                    }
                    ActionPreparationResult preparation = actionService.prepareCancellation(
                            context.conversationId(), candidate,
                            context.authorization(), context.userId());
                    return handlePreparation(context, preparation);
                });
    }

    private ToolCallback joinWaitlistPreparationCallback() {
        return callback(
                PatientActionService.PREPARE_JOIN_WAITLIST,
                "为 selectedCandidate 中由 Java 唯一确定且余号为 0 的 SCHEDULE_SLOT 生成加入候补确认卡。无业务 ID 参数；先查询包含无余号班次的号源并调用 select_patient_candidate。只有点击确认卡后才写入。"
                        + TERMINAL_ACTION_RETRY_RULE,
                json(parameters(Map.of(), List.of())),
                true,
                (input, toolContext) -> {
                    PatientToolExecutionContext context = state(toolContext);
                    context.recordTool(PatientActionService.PREPARE_JOIN_WAITLIST);
                    if (!emptyObject(input)) return direct(context,
                            "该工具不接受参数，请先通过 ResultSet 选择无余号班次。");
                    Candidate candidate = stateService.selectedCandidate(
                            context.conversationId()).orElse(null);
                    if (candidate == null || candidate.type() != CandidateType.SCHEDULE_SLOT) {
                        return direct(context, "尚未确定唯一班次，请先查询并选择号源。");
                    }
                    if (candidate.remainingCapacity() == null
                            || candidate.remainingCapacity() != 0) {
                        return direct(context, "所选班次仍有余号，应直接预约，不能加入候补。");
                    }
                    ActionPreparationResult preparation = actionService.prepareJoinWaitlist(
                            context.conversationId(), candidate,
                            context.authorization(), context.userId());
                    return handlePreparation(context, preparation);
                });
    }

    private ToolCallback cancelWaitlistPreparationCallback() {
        return callback(
                PatientActionService.PREPARE_CANCEL_WAITLIST,
                "为 selectedCandidate 中由 Java 唯一确定的 WAITING 候补生成取消确认卡。无业务 ID 参数；先调用 list_my_waitlists，再调用 select_patient_candidate 按序号或业务条件选择。模型不得生成 waitlistId。"
                        + TERMINAL_ACTION_RETRY_RULE,
                json(parameters(Map.of(), List.of())),
                true,
                (input, toolContext) -> {
                    PatientToolExecutionContext context = state(toolContext);
                    context.recordTool(PatientActionService.PREPARE_CANCEL_WAITLIST);
                    if (!emptyObject(input)) return direct(context,
                            "该工具不接受参数，请先通过 ResultSet 选择候补记录。");
                    Candidate candidate = stateService.selectedCandidate(
                            context.conversationId()).orElse(null);
                    if (candidate == null || candidate.type() != CandidateType.WAITLIST) {
                        return direct(context, "尚未确定唯一候补，请先查询并选择候补记录。");
                    }
                    if (!"WAITING".equals(candidate.status())) {
                        return direct(context, "只有 WAITING 状态的候补可以取消。");
                    }
                    ActionPreparationResult preparation = actionService.prepareCancelWaitlist(
                            context.conversationId(), candidate,
                            context.authorization(), context.userId());
                    return handlePreparation(context, preparation);
                });
    }

    private ToolCallback confirmWaitlistPreparationCallback() {
        return callback(
                PatientActionService.PREPARE_CONFIRM_WAITLIST,
                "为 selectedCandidate 中由 Java 唯一确定的 OFFERED 候补生成名额确认卡。确认成功会创建预约。无业务 ID 参数；先调用 list_my_waitlists，再选择 OFFERED 记录。模型不得生成 waitlistId。"
                        + TERMINAL_ACTION_RETRY_RULE,
                json(parameters(Map.of(), List.of())),
                true,
                (input, toolContext) -> {
                    PatientToolExecutionContext context = state(toolContext);
                    context.recordTool(PatientActionService.PREPARE_CONFIRM_WAITLIST);
                    if (!emptyObject(input)) return direct(context,
                            "该工具不接受参数，请先通过 ResultSet 选择候补记录。");
                    Candidate candidate = stateService.selectedCandidate(
                            context.conversationId()).orElse(null);
                    if (candidate == null || candidate.type() != CandidateType.WAITLIST) {
                        return direct(context, "尚未确定唯一候补，请先查询并选择候补记录。");
                    }
                    if (!"OFFERED".equals(candidate.status())) {
                        return direct(context, "只有 OFFERED 状态且尚未过期的候补名额可以确认。");
                    }
                    ActionPreparationResult preparation = actionService.prepareConfirmWaitlist(
                            context.conversationId(), candidate,
                            context.authorization(), context.userId());
                    return handlePreparation(context, preparation);
                });
    }

    private String handlePreparation(PatientToolExecutionContext context, ActionPreparationResult preparation) {
        if (!preparation.ready()) {
            propagateAuthenticationFailure(preparation.failure(), context);
            return direct(context, preparation.failure() == null || preparation.failure().error() == null
                    ? "无法准备操作，请重新查询后再试。"
                    : preparation.failure().error().message());
        }
        context.pendingAction(preparation.pendingAction());
        return direct(context, actionService.confirmationMessage(preparation.pendingAction()));
    }

    private ToolCallback ragCallback() {
        Map<String, Object> schema = parameters(
                Map.of("query", Map.of(
                        "type", "string", "minLength", 1, "maxLength", 1000,
                        "description", "结合当前问题和最近对话补全后的知识库检索问题")),
                List.of("query"));
        return callback(
                RAG_TOOL,
                "查询医院制度、退费规则、探视规定、报告领取、就医流程或医疗科普知识库。不得用于查询当前患者的预约或候补。此结果不包含可选择业务对象，不写 AgentState。",
                json(schema),
                true,
                (input, toolContext) -> {
                    PatientToolExecutionContext context = state(toolContext);
                    String query = requiredText(input, "query");
                    KnowledgeRagResponse rag = ragService.answer(
                            new KnowledgeRagRequest(query, PATIENT_RAG_TOP_K));
                    context.recordTool(RAG_TOOL);
                    context.ragResponse(rag);
                    context.directAnswer(rag.answer());
                    return rag.answer();
                });
    }

    private String toolResult(ToolExecutionResult execution, CandidateResultSet resultSet) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", execution.ok());
        response.put("data", execution.data());
        response.put("error", execution.error());
        if (resultSet != null) response.put("resultSet", resultSetFactory.referenceView(resultSet));
        return json(response);
    }

    private Map<String, Object> selectionView(CandidateSelectionResult selection) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("status", selection.status().name());
        view.put("message", selection.message());
        if (selection.resultSet() != null) {
            view.put("resultSet", resultSetFactory.referenceView(selection.resultSet()));
        }
        if (selection.selected() != null) {
            view.put("selected", Map.of(
                    "type", selection.selected().type().name(),
                    "displayText", selection.selected().displayText()));
        }
        return view;
    }

    private String formatSelection(CandidateSelectionResult selection) {
        if (selection.status() == CandidateSelectionResult.Status.SELECTED) return selection.message();
        if (selection.status() == CandidateSelectionResult.Status.AMBIGUOUS
                && selection.resultSet() != null) {
            return resultSetFactory.format(selection.resultSet());
        }
        return selection.message();
    }

    private String direct(PatientToolExecutionContext context, String answer) {
        context.directAnswer(answer);
        return answer;
    }

    private ToolCallback callback(
            String name,
            String description,
            String inputSchema,
            boolean returnDirect,
            ContextToolFunction function
    ) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name).description(description).inputSchema(inputSchema).build();
        ToolMetadata metadata = ToolMetadata.builder().returnDirect(returnDirect).build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public ToolMetadata getToolMetadata() { return metadata; }
            @Override public String call(String toolInput) {
                throw new IllegalStateException("Patient tools require a request ToolContext");
            }
            @Override public String call(String toolInput, ToolContext toolContext) {
                return function.call(toolInput, toolContext);
            }
        };
    }

    private PatientToolExecutionContext state(ToolContext context) {
        Object value = context.getContext().get(PatientToolExecutionContext.TOOL_CONTEXT_KEY);
        if (value instanceof PatientToolExecutionContext state) return state;
        throw new IllegalStateException("Patient tool execution context is missing");
    }

    private void propagateAuthenticationFailure(ToolExecutionResult result, PatientToolExecutionContext context) {
        if (result == null || result.ok() || result.error() == null) return;
        if ("AUTH_REQUIRED".equals(result.error().type())) {
            context.fatalException(new AgentException(AgentErrorCode.UNAUTHORIZED));
        } else if ("FORBIDDEN".equals(result.error().type())) {
            context.fatalException(new AgentException(AgentErrorCode.FORBIDDEN));
        }
    }

    private String requiredText(String input, String field) {
        try {
            var node = objectMapper.readTree(input == null || input.isBlank() ? "{}" : input);
            var value = node.get(field);
            if (value == null || !value.isTextual()) {
                throw new AgentException(AgentErrorCode.INVALID_RAG_QUESTION);
            }
            String text = value.textValue().strip();
            if (text.isEmpty() || text.length() > 1000) {
                throw new AgentException(AgentErrorCode.INVALID_RAG_QUESTION);
            }
            return text;
        } catch (JsonProcessingException exception) {
            throw new AgentException(AgentErrorCode.INVALID_RAG_QUESTION);
        }
    }

    private boolean emptyObject(String input) {
        try {
            var node = objectMapper.readTree(input == null || input.isBlank() ? "{}" : input);
            return node.isObject() && node.isEmpty();
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private Map<String, Object> parameters(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    private Map<String, Object> text(String description) {
        return Map.of("type", "string", "minLength", 1, "description", description);
    }

    private Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "minimum", 1, "description", description);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new AgentException(AgentErrorCode.INTERNAL_ERROR);
        }
    }

    @FunctionalInterface
    private interface ContextToolFunction {
        String call(String input, ToolContext context);
    }
}
