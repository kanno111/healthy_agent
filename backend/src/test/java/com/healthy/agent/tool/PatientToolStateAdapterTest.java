package com.healthy.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthy.agent.chat.PatientToolExecutionContext;
import com.healthy.agent.state.AgentStateService;
import com.healthy.agent.state.Candidate;
import com.healthy.agent.state.CandidateResultSet;
import com.healthy.agent.state.CandidateType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PatientToolStateAdapterTest {
    private static final String CONVERSATION =
            "patient:8:11111111-1111-4111-8111-111111111111";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentStateService stateService = new AgentStateService();
    private final PatientToolStateAdapter adapter =
            new PatientToolStateAdapter(stateService, objectMapper);

    @Test
    void resolvesDoctorIdOnlyFromReferencedResultSet() throws Exception {
        CandidateResultSet doctors = stateService.addResultSet(
                CONVERSATION, "本周值班医生", CandidateType.DOCTOR,
                List.of(doctor(11, "张医生"), doctor(15, "李医生")));

        PatientToolStateAdapter.NormalizedToolInput normalized = adapter.normalize(
                "list_schedule_slots",
                "{\"resultSetId\":\"" + doctors.resultSetId() + "\",\"position\":2,"
                        + "\"startDate\":\"2026-10-12\",\"endDate\":\"2026-10-18\"}",
                context());

        assertThat(normalized.ok()).isTrue();
        assertThat(objectMapper.readTree(normalized.json()).path("doctorId").longValue())
                .isEqualTo(15L);
    }

    @Test
    void refusesUnknownDoctorReferenceInsteadOfAcceptingModelId() {
        PatientToolStateAdapter.NormalizedToolInput normalized = adapter.normalize(
                "list_schedule_slots",
                "{\"doctorId\":999,\"startDate\":\"2026-10-12\","
                        + "\"endDate\":\"2026-10-18\"}",
                context());

        assertThat(normalized.ok()).isFalse();
        assertThat(normalized.error()).contains("未允许字段");
    }

    @Test
    void batchQueryExpandsOnlyDoctorsHeldInState() throws Exception {
        CandidateResultSet doctors = stateService.addResultSet(
                CONVERSATION, "下周医生", CandidateType.DOCTOR,
                List.of(doctor(11, "张医生"), doctor(15, "李医生")));

        PatientToolStateAdapter.NormalizedToolInput normalized = adapter.normalize(
                "list_doctors_schedule_slots",
                "{\"doctorResultSetId\":\"" + doctors.resultSetId() + "\","
                        + "\"startDate\":\"2026-10-12\",\"endDate\":\"2026-10-18\"}",
                context());

        assertThat(objectMapper.readTree(normalized.json()).path("doctorIds").toString())
                .isEqualTo("[11,15]");
    }

    private PatientToolExecutionContext context() {
        return new PatientToolExecutionContext(
                "查询", "Bearer token", 8L, CONVERSATION);
    }

    private Candidate doctor(long id, String name) {
        return new Candidate(id, CandidateType.DOCTOR, name + " 神经内科",
                id, name, 1L, "神经内科", null, null, null, null, null);
    }
}
