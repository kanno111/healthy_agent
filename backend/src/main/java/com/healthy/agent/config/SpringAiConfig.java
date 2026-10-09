package com.healthy.agent.config;

import com.healthy.agent.llm.ChatModelClient;
import com.healthy.agent.llm.SpringAiChatModelClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SpringAiConfig {
    public static final int MEMORY_MAX_MESSAGES = 40;

    @Bean
    ChatMemory patientChatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(MEMORY_MAX_MESSAGES)
                .build();
    }

    @Bean("patientChatClient")
    ChatClient patientChatClient(ChatClient.Builder builder, ChatMemory patientChatMemory) {
        return builder.clone()
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(patientChatMemory).build())
                .build();
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
