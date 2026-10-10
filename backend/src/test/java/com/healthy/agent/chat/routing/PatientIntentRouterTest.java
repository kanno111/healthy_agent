package com.healthy.agent.chat.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthy.agent.config.LlmProperties;
import com.healthy.agent.state.AgentStateService;
import com.healthy.agent.state.Candidate;
import com.healthy.agent.state.CandidateType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class PatientIntentRouterTest {
    private static final String CONVERSATION =
            "patient:8:11111111-1111-4111-8111-111111111111";

    private final ChatClient chatClient = mock(ChatClient.class);
    private final ChatClient.ChatClientRequestSpec request =
            mock(ChatClient.ChatClientRequestSpec.class);
    private final ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
    private final ChatMemory chatMemory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository())
            .maxMessages(40)
            .build();
    private final AgentStateService stateService = new AgentStateService();
    private final LlmProperties properties = new LlmProperties(
            "https://api.deepseek.com", "test-key", "deepseek-flash",
            2048, 0.1, Duration.ofSeconds(5), Duration.ofSeconds(30));
    private final PatientIntentRouter router = new PatientIntentRouter(
            chatClient, chatMemory, stateService, properties, new ObjectMapper());

    @BeforeEach
    void setUp() {
        when(chatClient.prompt()).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.options(any(ChatOptions.Builder.class))).thenReturn(request);
        when(request.call()).thenReturn(call);
    }

    @Test
    void recognizesMultipleRoutesWithoutFallingBackToAll() {
        when(call.chatResponse()).thenReturn(response(
                "{\"routes\":[\"QUERY\",\"WRITE\"],\"uncertain\":false,"
                        + "\"reason\":\"先查号源，无号时加入候补\"}", 45, 15));

        PatientIntentRoutingDecision result = router.route(
                "帮我看下周一有没有号，没有号就加入候补", CONVERSATION);

        assertThat(result.routes())
                .isEqualTo(Set.of(PatientIntentRoute.QUERY, PatientIntentRoute.WRITE));
        assertThat(result.fallbackToAll()).isFalse();
        assertThat(result.usage().totalTokens()).isEqualTo(60);
    }

    @Test
    void suppliesOnlyBoundedConversationAndIdFreeStateSummary() {
        chatMemory.add(CONVERSATION, new UserMessage("下周有哪些医生有号？"));
        chatMemory.add(CONVERSATION, new AssistantMessage("有张医生和李医生。"));
        stateService.addResultSet(CONVERSATION, "下周有号医生", CandidateType.DOCTOR,
                List.of(new Candidate(
                        987654L, CandidateType.DOCTOR, "李医生 神经内科",
                        987654L, "李医生", 66L, "神经内科",
                        null, null, null, null, null)));
        when(call.chatResponse()).thenReturn(response(
                "{\"routes\":[\"QUERY\"],\"uncertain\":false,"
                        + "\"reason\":\"引用既有医生列表\"}", 30, 10));

        router.route("刚才第二个医生呢？", CONVERSATION);

        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(request).user(input.capture());
        assertThat(input.getValue())
                .contains("刚才第二个医生呢", "下周有哪些医生有号", "下周有号医生", "type=DOCTOR")
                .doesNotContain("987654", "rs_");
    }

    @Test
    void uncertainMalformedAndConflictingOutputsFallBackToAll() {
        when(call.chatResponse()).thenReturn(response(
                "{\"routes\":[\"QUERY\"],\"uncertain\":true,\"reason\":\"指代不清\"}",
                10, 5));
        assertThat(router.route("还是那个", CONVERSATION).routes())
                .containsExactly(PatientIntentRoute.ALL);

        when(call.chatResponse()).thenReturn(response("not-json", 10, 5));
        assertThat(router.route("测试", CONVERSATION).fallbackToAll()).isTrue();

        when(call.chatResponse()).thenReturn(response(
                "{\"routes\":[\"CHAT\",\"WRITE\"],\"uncertain\":false}", 10, 5));
        assertThat(router.route("测试", CONVERSATION).fallbackToAll()).isTrue();
    }

    @Test
    void modelFailureDoesNotBlockMainAgentAndFallsBackToAll() {
        when(call.chatResponse()).thenThrow(new IllegalStateException("timeout"));

        PatientIntentRoutingDecision result = router.route("查询预约", CONVERSATION);

        assertThat(result.routes()).containsExactly(PatientIntentRoute.ALL);
        assertThat(result.fallbackToAll()).isTrue();
    }

    private ChatResponse response(String text, int promptTokens, int completionTokens) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder()
                        .model("deepseek-flash")
                        .usage(new DefaultUsage(promptTokens, completionTokens,
                                promptTokens + completionTokens))
                        .build());
    }
}
