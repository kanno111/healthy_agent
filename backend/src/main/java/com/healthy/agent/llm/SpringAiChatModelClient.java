package com.healthy.agent.llm;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Map;

public class SpringAiChatModelClient implements ChatModelClient {
    private static final Logger log = LoggerFactory.getLogger(SpringAiChatModelClient.class);

    private final ChatClient chatClient;
    private final LlmProperties properties;

    public SpringAiChatModelClient(ChatClient chatClient, LlmProperties properties) {
        this.chatClient = chatClient;
        this.properties = properties;
    }

    @Override
    public ChatModelResult generate(String systemPrompt, String userPrompt) {
        requireApiKey();
        try {
            ChatResponse response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .options(options())
                    .call()
                    .chatResponse();
            if (response == null || response.getResult() == null
                    || response.getResult().getOutput() == null
                    || response.getResult().getOutput().getText() == null
                    || response.getResult().getOutput().getText().isBlank()) {
                throw new AgentException(AgentErrorCode.LLM_UNAVAILABLE);
            }
            String model = response.getMetadata() == null
                    || response.getMetadata().getModel() == null
                    || response.getMetadata().getModel().isBlank()
                    ? properties.model() : response.getMetadata().getModel();
            return new ChatModelResult(
                    response.getResult().getOutput().getText().strip(),
                    model,
                    tokenUsage(response.getMetadata() == null
                            ? null : response.getMetadata().getUsage()));
        } catch (AgentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("Spring AI chat request failed: {}", exception.getClass().getSimpleName());
            throw new AgentException(AgentErrorCode.LLM_UNAVAILABLE);
        }
    }

    @Override
    public String model() {
        return properties.model();
    }

    public OpenAiChatOptions.Builder options() {
        return OpenAiChatOptions.builder()
                .model(properties.model())
                .temperature(properties.temperature())
                .maxTokens(properties.maxOutputTokens())
                .parallelToolCalls(false)
                .extraBody(Map.of("thinking", Map.of("type", "disabled")));
    }

    private TokenUsage tokenUsage(Usage usage) {
        if (usage == null) return TokenUsage.empty();
        return new TokenUsage(
                value(usage.getPromptTokens()),
                value(usage.getCompletionTokens()),
                value(usage.getTotalTokens()));
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private void requireApiKey() {
        if (properties.apiKey().isBlank()) {
            log.warn("LLM request skipped because the API key is not configured");
            throw new AgentException(AgentErrorCode.LLM_UNAVAILABLE);
        }
    }
}
