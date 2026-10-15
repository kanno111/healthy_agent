package com.healthy.agent.trace;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTraceRecorderTest {
    @Test
    void redactsCredentialsAndForbiddenBusinessIdentifiers() {
        AgentTraceRecorder recorder = AgentTraceRecorder.create();
        AgentTraceRecorder.StepHandle step = recorder.start(
                AgentTraceStepType.LLM, "测试步骤");

        step.success("Bearer secret-jwt", Map.of(
                "authorization", "Bearer secret-jwt",
                "userId", 8L,
                "promptTokens", 12,
                "nested", Map.of("appointmentId", 201L, "chunkCount", 3)));
        step.failure("第二次完成应被忽略");
        recorder.complete();

        assertThat(recorder.snapshot().steps()).singleElement().satisfies(recorded -> {
            assertThat(recorded.summary()).isEqualTo("[REDACTED]");
            assertThat(recorded.status()).isEqualTo(AgentTraceStatus.SUCCESS);
            assertThat(recorded.details())
                    .doesNotContainKeys("authorization", "userId")
                    .containsEntry("promptTokens", 12);
            assertThat(recorded.details().get("nested").toString())
                    .contains("chunkCount=3")
                    .doesNotContain("appointmentId", "201");
        });
    }
}
