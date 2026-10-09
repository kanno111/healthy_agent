package com.healthy.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthy.agent.state.AgentStateService;
import com.healthy.agent.state.CandidateResultSet;
import com.healthy.agent.state.CandidateType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PatientResultSetFactoryTest {
    private static final String CONVERSATION =
            "patient:8:11111111-1111-4111-8111-111111111111";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentStateService stateService = new AgentStateService();
    private final PatientResultSetFactory factory =
            new PatientResultSetFactory(stateService, objectMapper);

    @Test
    void allSevenPatientBusinessReadToolsCreateCompactResultSets() {
        var departments = objectMapper.createArrayNode().add(
                objectMapper.createObjectNode().put("id", 1).put("name", "神经内科"));
        CandidateResultSet departmentSet = factory.create(
                CONVERSATION, "list_departments", "{}", departments);

        var doctor = objectMapper.createObjectNode()
                .put("id", 11).put("name", "张医生")
                .put("departmentId", 1).put("departmentName", "神经内科")
                .put("title", "主任医师").put("hasAvailableSlots", true);
        var doctors = objectMapper.createObjectNode();
        doctors.putArray("records").add(doctor);
        CandidateResultSet doctorSet = factory.create(
                CONVERSATION, "search_doctors", "{}", doctors);
        CandidateResultSet doctorDetailSet = factory.create(
                CONVERSATION, "get_doctor_detail", "{\"doctorId\":11}", doctor);

        var slot = objectMapper.createObjectNode()
                .put("id", 101).put("scheduleDate", "2026-10-12")
                .put("sessionType", "MORNING").put("sessionName", "上午门诊")
                .put("startTime", "08:00:00").put("endTime", "12:00:00")
                .put("remainingCapacity", 2);
        CandidateResultSet slotSet = factory.create(
                CONVERSATION, "list_schedule_slots",
                "{\"doctorId\":11,\"startDate\":\"2026-10-12\",\"endDate\":\"2026-10-12\"}",
                objectMapper.createArrayNode().add(slot));

        var batch = objectMapper.createObjectNode();
        batch.putArray("doctors").add(objectMapper.createObjectNode()
                .put("doctorId", 11).set("slots", objectMapper.createArrayNode().add(slot)));
        CandidateResultSet batchSet = factory.create(
                CONVERSATION, "list_doctors_schedule_slots",
                "{\"doctorIds\":[11],\"startDate\":\"2026-10-12\",\"endDate\":\"2026-10-18\"}",
                batch, "下周谁有号？");

        var appointment = objectMapper.createObjectNode()
                .put("id", 201).put("doctorId", 11).put("doctorName", "张医生")
                .put("departmentId", 1).put("departmentName", "神经内科")
                .put("scheduleDate", "2026-10-12").put("sessionName", "上午门诊")
                .put("status", "BOOKED");
        CandidateResultSet appointmentSet = factory.create(
                CONVERSATION, "list_my_appointments", "{}",
                objectMapper.createArrayNode().add(appointment));

        var waitlist = objectMapper.createObjectNode()
                .put("id", 301).put("doctorId", 11).put("doctorName", "张医生")
                .put("departmentId", 1).put("departmentName", "神经内科")
                .put("scheduleDate", "2026-10-12").put("sessionName", "上午门诊")
                .put("startTime", "08:00:00").put("endTime", "12:00:00")
                .put("status", "OFFERED")
                .put("offerExpireTime", "2026-10-08T14:03:00");
        CandidateResultSet waitlistSet = factory.create(
                CONVERSATION, "list_my_waitlists", "{}",
                objectMapper.createArrayNode().add(waitlist));

        assertThat(departmentSet.type()).isEqualTo(CandidateType.DEPARTMENT);
        assertThat(doctorSet.type()).isEqualTo(CandidateType.DOCTOR);
        assertThat(doctorDetailSet.type()).isEqualTo(CandidateType.DOCTOR);
        assertThat(slotSet.type()).isEqualTo(CandidateType.SCHEDULE_SLOT);
        assertThat(batchSet.type()).isEqualTo(CandidateType.SCHEDULE_SLOT);
        assertThat(batchSet.description()).contains("下周谁有号");
        assertThat(appointmentSet.type()).isEqualTo(CandidateType.APPOINTMENT);
        assertThat(waitlistSet.type()).isEqualTo(CandidateType.WAITLIST);
        assertThat(waitlistSet.candidates().getFirst().displayText())
                .contains("待确认名额", "08:00-12:00", "确认截止 2026-10-08 14:03:00");
        assertThat(stateService.getOrCreate(CONVERSATION).recentResultSets()).hasSize(7);
        assertThat(slotSet.candidates().getFirst().businessId()).isEqualTo(101L);
        assertThat(slotSet.candidates().getFirst().displayText()).contains("张医生");
    }
}
