package com.healthy.agent.memory.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthy.agent.common.AgentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatientProfileServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    private final PatientProfileService service = new PatientProfileService(
            redis, new ObjectMapper().findAndRegisterModules(),
            Clock.fixed(Instant.parse("2026-10-15T06:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(redis.opsForHash()).thenReturn(hashes);
    }

    @Test
    void readsEnumsFromUserScopedRedisHash() {
        when(hashes.entries(anyString())).thenReturn(Map.ofEntries(
                Map.entry("appointmentPeriod", "AFTERNOON"),
                Map.entry("responseStyle", "CONCISE"),
                Map.entry("age", "32"),
                Map.entry("sex", "MALE"),
                Map.entry("heightCm", "175"),
                Map.entry("weightKg", "70.5"),
                Map.entry("conditions", "[\"高血压\"]"),
                Map.entry("allergies", "[\"青霉素\"]"),
                Map.entry("medications", "[\"二甲双胍 500mg\"]"),
                Map.entry("smokingStatus", "FORMER"),
                Map.entry("alcoholStatus", "NEVER"),
                Map.entry("updatedAt", "2026-10-15T05:00:00Z")));

        PatientProfile profile = service.get(8L);

        assertThat(profile.appointmentPeriod()).isEqualTo(AppointmentPeriod.AFTERNOON);
        assertThat(profile.responseStyle()).isEqualTo(ResponseStyle.CONCISE);
        assertThat(profile.age()).isEqualTo(32);
        assertThat(profile.sex()).isEqualTo(Sex.MALE);
        assertThat(profile.heightCm()).isEqualTo(175);
        assertThat(profile.weightKg()).isEqualByComparingTo("70.5");
        assertThat(profile.conditions()).containsExactly("高血压");
        assertThat(profile.allergies()).containsExactly("青霉素");
        assertThat(profile.medications()).containsExactly("二甲双胍 500mg");
        assertThat(profile.smokingStatus()).isEqualTo(SmokingStatus.FORMER);
        assertThat(profile.alcoholStatus()).isEqualTo(AlcoholStatus.NEVER);
        verify(hashes).entries("healthy-agent:memory:profile:patient:8");
    }

    @Test
    @SuppressWarnings("unchecked")
    void addsAndRemovesCollectionItemsWithoutReplacingUnrelatedFields() {
        when(hashes.entries(anyString())).thenReturn(Map.of(
                "appointmentPeriod", "MORNING",
                "responseStyle", "DETAILED",
                "conditions", "[\"高血压\"]",
                "allergies", "[\"青霉素\",\"花生\"]",
                "medications", "[]"));
        PatientProfilePatch patch = new PatientProfilePatch(
                ProfileFieldPatch.keep(),
                ProfileFieldPatch.keep(),
                ProfileFieldPatch.keep(),
                ProfileFieldPatch.keep(),
                ProfileFieldPatch.keep(),
                ProfileFieldPatch.keep(),
                new ProfileCollectionPatch(ProfilePatchOperation.ADD, List.of("糖尿病")),
                new ProfileCollectionPatch(ProfilePatchOperation.REMOVE, List.of("花生")),
                ProfileCollectionPatch.keep(),
                ProfileFieldPatch.keep(),
                ProfileFieldPatch.keep());

        service.applyPatch(8L, patch);

        ArgumentCaptor<Map<String, String>> updates = ArgumentCaptor.forClass(Map.class);
        verify(hashes).putAll(
                org.mockito.ArgumentMatchers.eq("healthy-agent:memory:profile:patient:8"),
                updates.capture());
        assertThat(updates.getValue())
                .containsEntry("conditions", "[\"高血压\",\"糖尿病\"]")
                .containsEntry("allergies", "[\"青霉素\"]")
                .doesNotContainKeys("appointmentPeriod", "responseStyle", "medications");
    }

    @Test
    @SuppressWarnings("unchecked")
    void appliesOnlyChangedFieldsSoConcurrentFieldsAreNotOverwritten() {
        when(hashes.entries(anyString())).thenReturn(Map.of(
                "appointmentPeriod", "MORNING",
                "responseStyle", "DETAILED",
                "updatedAt", "2026-10-15T06:00:00Z"));
        PatientProfilePatch patch = new PatientProfilePatch(
                new ProfileFieldPatch(ProfilePatchOperation.SET, "MORNING"),
                ProfileFieldPatch.keep());

        PatientProfile result = service.applyPatch(8L, patch);

        ArgumentCaptor<Map<String, String>> updates = ArgumentCaptor.forClass(Map.class);
        verify(hashes).putAll(
                org.mockito.ArgumentMatchers.eq("healthy-agent:memory:profile:patient:8"),
                updates.capture());
        assertThat(updates.getValue()).containsEntry("appointmentPeriod", "MORNING")
                .containsEntry("updatedAt", "2026-10-15T06:00:00Z")
                .doesNotContainKey("responseStyle");
        assertThat(result.responseStyle()).isEqualTo(ResponseStyle.DETAILED);
    }

    @Test
    void promptContainsOnlyControlledLabels() {
        when(hashes.entries(anyString())).thenReturn(Map.of(
                "appointmentPeriod", "MORNING",
                "responseStyle", "DETAILED",
                "age", "32",
                "conditions", "[\"高血压\"]",
                "allergies", "[\"青霉素\"]"));

        assertThat(service.promptSummary(8L))
                .contains("用户自述健康信息，未经医院确认")
                .contains("预约时段偏好：上午")
                .contains("回答风格：详细")
                .contains("用户自述年龄：32岁")
                .contains("用户自述已确诊疾病/慢性病：高血压")
                .contains("用户自述过敏史：青霉素");
    }

    @Test
    void rejectsOutOfRangeHealthMeasurementsBeforeWritingRedis() {
        PatientProfileUpdateRequest update = new PatientProfileUpdateRequest(
                AppointmentPeriod.NO_PREFERENCE,
                ResponseStyle.DEFAULT,
                151,
                Sex.UNKNOWN,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                SmokingStatus.UNKNOWN,
                AlcoholStatus.UNKNOWN);

        assertThatThrownBy(() -> service.replace(8L, update))
                .isInstanceOf(AgentException.class);
    }
}
