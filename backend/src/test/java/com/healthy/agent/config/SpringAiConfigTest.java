package com.healthy.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiConfigTest {
    @Test
    void keepsFortyRecentMessagesAndSeparatesConversations() {
        ChatMemory memory = new SpringAiConfig().patientChatMemory();
        for (int index = 0; index < 45; index++) {
            memory.add("patient:8:conversation-a", new UserMessage("message-" + index));
        }
        memory.add("patient:8:conversation-b", new UserMessage("other conversation"));

        assertThat(memory.get("patient:8:conversation-a"))
                .hasSize(SpringAiConfig.MEMORY_MAX_MESSAGES)
                .first()
                .extracting(message -> message.getText())
                .isEqualTo("message-5");
        assertThat(memory.get("patient:8:conversation-b"))
                .singleElement()
                .extracting(message -> message.getText())
                .isEqualTo("other conversation");
    }
}
