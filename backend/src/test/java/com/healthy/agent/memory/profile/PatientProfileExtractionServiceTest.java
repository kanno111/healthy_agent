package com.healthy.agent.memory.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthy.agent.llm.ChatModelClient;
import com.healthy.agent.llm.ChatModelResult;
import com.healthy.agent.llm.TokenUsage;
import com.healthy.agent.trace.AgentTraceRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatientProfileExtractionServiceTest {
    private final ChatModelClient model = mock(ChatModelClient.class);
    private final PatientProfileService profiles = mock(PatientProfileService.class);
    private final PatientProfileExtractionService service =
            new PatientProfileExtractionService(
                    model, profiles, new ObjectMapper().findAndRegisterModules());

    @Test
    void parsesValidatedPatchAndUsesOnlyCurrentProfileAndMessage() {
        when(profiles.getOrEmpty(8L)).thenReturn(new PatientProfile(
                AppointmentPeriod.NO_PREFERENCE, ResponseStyle.DEFAULT, null));
        when(model.generate(anyString(), anyString(), anyInt(), anyDouble()))
                .thenReturn(new ChatModelResult("""
                        {"appointmentPeriod":{"operation":"SET","value":"MORNING"},
                         "responseStyle":{"operation":"KEEP","value":""},
                         "age":{"operation":"SET","value":"32"},
                         "sex":{"operation":"SET","value":"MALE"},
                         "heightCm":{"operation":"SET","value":"175"},
                         "weightKg":{"operation":"SET","value":"70.5"},
                         "conditions":{"operation":"ADD","values":["高血压"]},
                         "allergies":{"operation":"ADD","values":["青霉素"]},
                         "medications":{"operation":"ADD","values":["二甲双胍 500mg"]},
                         "smokingStatus":{"operation":"SET","value":"FORMER"},
                         "alcoholStatus":{"operation":"SET","value":"NEVER"}}
                        """, "deepseek-flash", new TokenUsage(40, 15, 55)));

        PatientProfileExtractionResult result = service.extract(
                8L, "请记住我以后更喜欢上午", AgentTraceRecorder.create());

        assertThat(result.patch().appointmentPeriod().operation())
                .isEqualTo(ProfilePatchOperation.SET);
        assertThat(result.patch().appointmentPeriod().value()).isEqualTo("MORNING");
        assertThat(result.patch().responseStyle().operation())
                .isEqualTo(ProfilePatchOperation.KEEP);
        assertThat(result.patch().age().value()).isEqualTo("32");
        assertThat(result.patch().conditions().values()).containsExactly("高血压");
        assertThat(result.patch().allergies().values()).containsExactly("青霉素");
        assertThat(result.patch().medications().values()).containsExactly("二甲双胍 500mg");
        assertThat(result.usage().totalTokens()).isEqualTo(55);
        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(model).generate(anyString(), input.capture(),
                org.mockito.ArgumentMatchers.eq(500), org.mockito.ArgumentMatchers.eq(0.0));
        assertThat(input.getValue())
                .contains("NO_PREFERENCE", "请记住我以后更喜欢上午")
                .doesNotContain("assistant", "JWT");
    }

    @Test
    void invalidModelOutputFailsOpenAndKeepsUsage() {
        when(profiles.getOrEmpty(8L)).thenReturn(PatientProfile.empty());
        when(model.generate(anyString(), anyString(), anyInt(), anyDouble()))
                .thenReturn(new ChatModelResult(
                        "not-json", "deepseek-flash", new TokenUsage(20, 3, 23)));

        PatientProfileExtractionResult result = service.extract(
                8L, "普通消息", AgentTraceRecorder.create());

        assertThat(result.patch().changesAnything()).isFalse();
        assertThat(result.usage().totalTokens()).isEqualTo(23);
    }
}
