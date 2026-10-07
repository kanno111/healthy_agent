package com.healthy.agent.llm;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.config.LlmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DeepSeekChatClientTest {

    @Test
    void callsNonThinkingChatCompletionAndReturnsUsage() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://llm.example");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DeepSeekChatClient client = new DeepSeekChatClient(builder.build(), properties("test-key"));

        server.expect(requestTo("https://llm.example/chat/completions"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("deepseek-flash"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].content").value("参考资料和问题"))
                .andExpect(jsonPath("$.thinking.type").value("disabled"))
                .andExpect(jsonPath("$.max_tokens").value(600))
                .andExpect(jsonPath("$.stream").value(false))
                .andRespond(withSuccess("""
                        {
                          "model":"deepseek-flash",
                          "choices":[{"message":{"role":"assistant","content":" 可在就诊日前取消。[资料1] "}}],
                          "usage":{"prompt_tokens":120,"completion_tokens":18,"total_tokens":138}
                        }
                        """, MediaType.APPLICATION_JSON));

        ChatModelResult result = client.generate("系统要求", "参考资料和问题");

        assertThat(result.answer()).isEqualTo("可在就诊日前取消。[资料1]");
        assertThat(result.model()).isEqualTo("deepseek-flash");
        assertThat(result.usage()).isEqualTo(new TokenUsage(120, 18, 138));
        server.verify();
    }

    @Test
    void rejectsRequestWhenApiKeyIsMissing() {
        DeepSeekChatClient client = new DeepSeekChatClient(
                RestClient.builder().baseUrl("https://llm.example").build(), properties(""));

        assertThatThrownBy(() -> client.generate("system", "user"))
                .isInstanceOfSatisfying(AgentException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(AgentErrorCode.LLM_UNAVAILABLE));
    }

    @Test
    void doesNotExposeApiKeyInPropertiesToString() {
        assertThat(properties("test-key").toString())
                .doesNotContain("test-key")
                .contains("<redacted>");
    }

    private LlmProperties properties(String apiKey) {
        return new LlmProperties(
                "https://llm.example", apiKey, "deepseek-flash",
                600, 0.2, Duration.ofSeconds(5), Duration.ofSeconds(90)
        );
    }
}
