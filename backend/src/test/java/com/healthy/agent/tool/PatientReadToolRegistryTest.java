package com.healthy.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatientReadToolRegistryTest {
    private static final String AUTHORIZATION = "Bearer patient-jwt";
    private final HealthyApiClient client = mock(HealthyApiClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-10-07T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private final PatientReadToolRegistry registry =
            new PatientReadToolRegistry(client, objectMapper, clock);

    @Test
    void exposesSevenReadToolsIncludingBatchSchedules() {
        List<String> names = registry.definitions().stream()
                .map(definition -> (Map<?, ?>) definition.get("function"))
                .map(function -> (String) function.get("name"))
                .toList();

        assertThat(names).containsExactly(
                "list_departments", "search_doctors", "get_doctor_detail",
                "list_schedule_slots", "list_doctors_schedule_slots",
                "list_my_appointments", "list_my_waitlists");
        assertThat(names).noneMatch(name -> name.startsWith("create_")
                || name.startsWith("cancel_") || name.startsWith("confirm_"));

        Map<?, ?> appointmentTool = registry.definitions().stream()
                .map(definition -> (Map<?, ?>) definition.get("function"))
                .filter(function -> "list_my_appointments".equals(function.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat((String) appointmentTool.get("description"))
                .contains("预约 ResultSet")
                .contains("select_patient_candidate")
                .contains("禁止生成 appointmentId");

        for (Map<String, Object> definition : registry.definitions()) {
            Map<?, ?> function = (Map<?, ?>) definition.get("function");
            String schema = function.get("parameters").toString();
            assertThat(schema)
                    .doesNotContain("doctorId=")
                    .doesNotContain("doctorIds=")
                    .doesNotContain("departmentId=")
                    .doesNotContain("appointmentId=")
                    .doesNotContain("scheduleSlotId=");
        }
    }

    @Test
    void searchDoctorsAddsDefaultsAndPassesRawKeywordForSingleEncoding() {
        Map<String, Object> expectedQuery = Map.of(
                "page", 1,
                "pageSize", 100,
                "keyword", "心血管");
        when(client.get("/api/user/doctors", expectedQuery, AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(objectMapper.createArrayNode()));

        ToolExecutionResult result = registry.execute(
                "search_doctors", "{\"keyword\":\"心血管\"}", AUTHORIZATION, 8L);

        assertThat(result.ok()).isTrue();
        verify(client).get("/api/user/doctors", expectedQuery, AUTHORIZATION);
    }

    @Test
    void searchDoctorsWithoutFiltersLoadsAllDoctorsInOnePage() {
        Map<String, Object> expectedQuery = Map.of("page", 1, "pageSize", 100);
        when(client.get("/api/user/doctors", expectedQuery, AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(objectMapper.createObjectNode()
                        .putArray("records")));

        ToolExecutionResult result = registry.execute(
                "search_doctors", "{}", AUTHORIZATION, 8L);

        assertThat(result.ok()).isTrue();
        verify(client).get("/api/user/doctors", expectedQuery, AUTHORIZATION);
    }

    @Test
    void rejectsScheduleRangeLongerThanFourteenDaysBeforeHttpCall() {
        ToolExecutionResult result = registry.execute(
                "list_schedule_slots",
                "{\"doctorId\":1,\"startDate\":\"2026-10-07\",\"endDate\":\"2026-10-21\"}",
                AUTHORIZATION,
                8L
        );

        assertThat(result.ok()).isFalse();
        assertThat(result.error().type()).isEqualTo("INVALID_ARGUMENT");
        verify(client, never()).get(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void batchSchedulesDeduplicatesDoctorsFiltersFullSlotsAndKeepsPartialFailures() {
        Map<String, Object> dateQuery = Map.of(
                "startDate", java.time.LocalDate.parse("2026-10-12"),
                "endDate", java.time.LocalDate.parse("2026-10-18"));
        var firstDoctorSlots = objectMapper.createArrayNode();
        firstDoctorSlots.addObject()
                .put("id", 101)
                .put("scheduleDate", "2026-10-12")
                .put("remainingCapacity", 3);
        firstDoctorSlots.addObject()
                .put("id", 102)
                .put("scheduleDate", "2026-10-13")
                .put("remainingCapacity", 0);
        when(client.get("/api/user/doctors/1/schedule-slots", dateQuery, AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(firstDoctorSlots));
        when(client.get("/api/user/doctors/2/schedule-slots", dateQuery, AUTHORIZATION))
                .thenReturn(ToolExecutionResult.success(objectMapper.createArrayNode()));
        when(client.get("/api/user/doctors/3/schedule-slots", dateQuery, AUTHORIZATION))
                .thenReturn(ToolExecutionResult.failure(new ToolError(
                        503, 50300, "DEPENDENCY_UNAVAILABLE", "服务暂不可用", true)));

        ToolExecutionResult result = registry.execute(
                "list_doctors_schedule_slots",
                "{\"doctorIds\":[1,2,1,3],\"startDate\":\"2026-10-12\","
                        + "\"endDate\":\"2026-10-18\",\"onlyAvailable\":true}",
                AUTHORIZATION,
                8L
        );

        assertThat(result.ok()).isTrue();
        assertThat(result.data().path("requestedDoctorCount").asInt()).isEqualTo(3);
        assertThat(result.data().path("successfulDoctorCount").asInt()).isEqualTo(2);
        assertThat(result.data().path("availableDoctorCount").asInt()).isEqualTo(1);
        assertThat(result.data().path("partial").asBoolean()).isTrue();
        assertThat(result.data().path("doctors")).hasSize(1);
        assertThat(result.data().path("doctors").get(0).path("doctorId").asLong())
                .isEqualTo(1L);
        assertThat(result.data().path("doctors").get(0).path("slots")).hasSize(1);
        assertThat(result.data().path("failures")).hasSize(1);
        assertThat(result.data().path("failures").get(0).path("doctorId").asLong())
                .isEqualTo(3L);
        verify(client).get("/api/user/doctors/1/schedule-slots", dateQuery, AUTHORIZATION);
    }
}
