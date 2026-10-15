package com.healthy.agent.config;

import com.healthy.agent.llm.ChatModelClient;
import com.healthy.agent.llm.SpringAiChatModelClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SpringAiConfig {
    @Bean("patientChatClient")
    ChatClient patientChatClient(ChatClient.Builder builder) {
        return builder.clone().build();
    }

    @Bean("statelessChatClient")
    ChatClient statelessChatClient(ChatClient.Builder builder) {
        return builder.clone().build();
    }

    @Bean
    ChatModelClient chatModelClient(
            @org.springframework.beans.factory.annotation.Qualifier("statelessChatClient")
            ChatClient chatClient,
            LlmProperties properties
    ) {
        return new SpringAiChatModelClient(chatClient, properties);
    }
}
