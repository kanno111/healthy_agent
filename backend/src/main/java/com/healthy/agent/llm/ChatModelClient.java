package com.healthy.agent.llm;

public interface ChatModelClient {
    ChatModelResult generate(String systemPrompt, String userPrompt);

    String model();
}
