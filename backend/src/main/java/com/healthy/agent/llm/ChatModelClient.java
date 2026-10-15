package com.healthy.agent.llm;

public interface ChatModelClient {
    ChatModelResult generate(String systemPrompt, String userPrompt);

    default ChatModelResult generate(
            String systemPrompt,
            String userPrompt,
            int maxOutputTokens
    ) {
        return generate(systemPrompt, userPrompt);
    }

    default ChatModelResult generate(
            String systemPrompt,
            String userPrompt,
            int maxOutputTokens,
            double temperature
    ) {
        return generate(systemPrompt, userPrompt, maxOutputTokens);
    }

    String model();
}
