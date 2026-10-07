package com.healthy.agent.llm;

public record ChatModelResult(
        String answer,
        String model,
        TokenUsage usage
) {
}
