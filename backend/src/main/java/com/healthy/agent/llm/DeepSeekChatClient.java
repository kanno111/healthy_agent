package com.healthy.agent.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DeepSeekChatClient implements ChatModelClient {
    private static final Logger log = LoggerFactory.getLogger(DeepSeekChatClient.class);

    private final RestClient restClient;
    private final LlmProperties properties;

    @Autowired
    public DeepSeekChatClient(RestClient.Builder builder, LlmProperties properties) {
        this(createRestClient(builder, properties), properties);
    }

    DeepSeekChatClient(RestClient restClient, LlmProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public ChatModelResult generate(String systemPrompt, String userPrompt) {
        if (properties.apiKey().isBlank()) {
            log.warn("LLM request skipped because the API key is not configured");
            throw new AgentException(AgentErrorCode.LLM_UNAVAILABLE);
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", properties.model());
        request.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        request.put("thinking", Map.of("type", "disabled"));
        request.put("temperature", properties.temperature());
        request.put("max_tokens", properties.maxOutputTokens());
        request.put("stream", false);

        try {
            ChatCompletionResponse response = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .body(request)
                    .retrieve()
                    .body(ChatCompletionResponse.class);
            return validateResponse(response);
        } catch (AgentException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            log.warn("LLM provider returned HTTP {}", exception.getStatusCode().value());
            throw new AgentException(AgentErrorCode.LLM_UNAVAILABLE);
        } catch (ResourceAccessException exception) {
            log.warn("LLM provider request failed: {}", exception.getClass().getSimpleName());
            throw new AgentException(AgentErrorCode.LLM_UNAVAILABLE);
        } catch (RuntimeException exception) {
            log.warn("LLM response could not be processed: {}", exception.getClass().getSimpleName());
            throw new AgentException(AgentErrorCode.LLM_UNAVAILABLE);
        }
    }

    @Override
    public String model() {
        return properties.model();
    }

    private ChatModelResult validateResponse(ChatCompletionResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()
                || response.choices().getFirst() == null
                || response.choices().getFirst().message() == null
                || response.choices().getFirst().message().content() == null
                || response.choices().getFirst().message().content().isBlank()) {
            throw new AgentException(AgentErrorCode.LLM_UNAVAILABLE);
        }

        Usage usage = response.usage();
        TokenUsage tokenUsage = usage == null
                ? TokenUsage.empty()
                : new TokenUsage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
        String responseModel = response.model() == null || response.model().isBlank()
                ? properties.model() : response.model();
        return new ChatModelResult(
                response.choices().getFirst().message().content().strip(),
                responseModel,
                tokenUsage
        );
    }

    private static RestClient createRestClient(RestClient.Builder builder, LlmProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.toIntExact(properties.connectTimeout().toMillis()));
        requestFactory.setReadTimeout(Math.toIntExact(properties.readTimeout().toMillis()));
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    private record ChatCompletionResponse(
            String model,
            List<Choice> choices,
            Usage usage
    ) {
    }

    private record Choice(Message message) {
    }

    private record Message(String content) {
    }

    private record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens
    ) {
    }
}
