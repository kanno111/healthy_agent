package com.healthy.agent.chat.routing;

import com.healthy.agent.action.PatientActionService;
import com.healthy.agent.tool.PatientSpringAiTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PatientIntentToolSelectorTest {
    private static final List<String> QUERY_TOOLS = List.of(
            "list_departments",
            "search_doctors",
            "get_doctor_detail",
            "list_schedule_slots",
            "list_doctors_schedule_slots",
            "list_my_appointments",
            "list_my_waitlists",
            PatientSpringAiTools.SELECT_CANDIDATE);
    private static final List<String> PREPARE_TOOLS = List.of(
            PatientActionService.PREPARE_CREATE_APPOINTMENT,
            PatientActionService.PREPARE_CANCEL_APPOINTMENT,
            PatientActionService.PREPARE_JOIN_WAITLIST,
            PatientActionService.PREPARE_CANCEL_WAITLIST,
            PatientActionService.PREPARE_CONFIRM_WAITLIST);

    private final PatientIntentToolSelector selector = new PatientIntentToolSelector();
    private final List<ToolCallback> callbacks = callbacks();

    @Test
    void chatGetsNoToolsAndRagGetsOnlyKnowledgeTool() {
        assertThat(names(select(PatientIntentRoute.CHAT))).isEmpty();
        assertThat(names(select(PatientIntentRoute.RAG)))
                .containsExactly(PatientSpringAiTools.RAG_TOOL);
    }

    @Test
    void queryGetsReadAndSelectionToolsWithoutWritesOrRag() {
        assertThat(names(select(PatientIntentRoute.QUERY)))
                .containsExactlyElementsOf(QUERY_TOOLS);
    }

    @Test
    void writeGetsQueryPrerequisitesAndPrepareTools() {
        assertThat(names(select(PatientIntentRoute.WRITE)))
                .containsExactlyElementsOf(concat(QUERY_TOOLS, PREPARE_TOOLS))
                .doesNotContain(PatientSpringAiTools.RAG_TOOL);
    }

    @Test
    void multipleRoutesUseUnionAndAllReturnsEverySafeCallback() {
        PatientIntentRoutingDecision queryAndRag = PatientIntentRoutingDecision.routed(
                Set.of(PatientIntentRoute.QUERY, PatientIntentRoute.RAG), "", null);
        assertThat(names(selector.select(callbacks, queryAndRag)))
                .containsExactlyElementsOf(concat(QUERY_TOOLS,
                        List.of(PatientSpringAiTools.RAG_TOOL)));

        assertThat(names(select(PatientIntentRoute.ALL)))
                .containsExactlyElementsOf(names(callbacks));
    }

    @Test
    void narrowRoutesRejectToolsThatHaveNotBeenExplicitlyGrouped() {
        List<ToolCallback> withFutureTool = concat(callbacks, List.of(callback("future_tool")));
        PatientIntentRoutingDecision query = PatientIntentRoutingDecision.routed(
                Set.of(PatientIntentRoute.QUERY), "", null);

        assertThat(names(selector.select(withFutureTool, query)))
                .doesNotContain("future_tool");
        assertThat(names(selector.select(withFutureTool,
                PatientIntentRoutingDecision.fallback("", null))))
                .contains("future_tool");
    }

    private List<ToolCallback> select(PatientIntentRoute route) {
        PatientIntentRoutingDecision decision = route == PatientIntentRoute.ALL
                ? PatientIntentRoutingDecision.fallback("test", null)
                : PatientIntentRoutingDecision.routed(Set.of(route), "test", null);
        return selector.select(callbacks, decision);
    }

    private List<ToolCallback> callbacks() {
        return concat(QUERY_TOOLS, List.of(PatientSpringAiTools.RAG_TOOL), PREPARE_TOOLS)
                .stream().map(this::callback).toList();
    }

    @SafeVarargs
    private final <T> List<T> concat(List<T>... lists) {
        return java.util.stream.Stream.of(lists).flatMap(List::stream).toList();
    }

    private List<String> names(List<ToolCallback> values) {
        return values.stream().map(value -> value.getToolDefinition().name()).toList();
    }

    private ToolCallback callback(String name) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name).description(name).inputSchema("{\"type\":\"object\"}").build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public ToolMetadata getToolMetadata() { return ToolMetadata.builder().build(); }
            @Override public String call(String toolInput) { return ""; }
        };
    }
}
