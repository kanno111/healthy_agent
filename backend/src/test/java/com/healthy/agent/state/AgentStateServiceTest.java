package com.healthy.agent.state;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStateServiceTest {
    private static final String CONVERSATION = "patient:8:11111111-1111-4111-8111-111111111111";

    private final AgentStateService service = new AgentStateService();

    @Test
    void keepsRecentResultSetsAndCanReturnToOlderResult() {
        CandidateResultSet thisWeek = service.addResultSet(
                CONVERSATION, "本周值班医生", CandidateType.DOCTOR,
                List.of(doctor(11, "张医生"), doctor(15, "李医生")));
        CandidateResultSet nextWeek = service.addResultSet(
                CONVERSATION, "下周值班医生", CandidateType.DOCTOR,
                List.of(doctor(21, "王医生"), doctor(25, "陈医生")));

        CandidateSelectionResult currentSecond = service.selectCandidate(
                CONVERSATION,
                new CandidateSelector(null, 2, null, null, null, null, null, null));
        CandidateSelectionResult olderSecond = service.selectCandidate(
                CONVERSATION,
                new CandidateSelector(thisWeek.resultSetId(), 2, null,
                        null, null, null, null, null));

        assertThat(currentSecond.selected().businessId()).isEqualTo(25L);
        assertThat(olderSecond.selected().businessId()).isEqualTo(15L);
        assertThat(service.getOrCreate(CONVERSATION).recentResultSets())
                .extracting(CandidateResultSet::resultSetId)
                .containsExactly(thisWeek.resultSetId(), nextWeek.resultSetId());
    }

    @Test
    void filtersScheduleSlotsAndRequiresFurtherSelectionWhenMultipleRemain() {
        service.addResultSet(CONVERSATION, "下周号源", CandidateType.SCHEDULE_SLOT,
                List.of(
                        slot(101, "2026-10-12", "上午门诊"),
                        slot(102, "2026-10-12", "上午专家门诊"),
                        slot(103, "2026-10-12", "下午门诊")));

        CandidateSelectionResult monday = service.selectCandidate(
                CONVERSATION,
                new CandidateSelector(null, null, CandidateType.SCHEDULE_SLOT,
                        null, null, "2026-10-12", null, null));
        CandidateSelectionResult morning = service.selectCandidate(
                CONVERSATION,
                new CandidateSelector(null, null, CandidateType.SCHEDULE_SLOT,
                        null, null, null, "上午", null));

        assertThat(monday.status()).isEqualTo(CandidateSelectionResult.Status.AMBIGUOUS);
        assertThat(monday.candidates()).hasSize(3);
        assertThat(morning.status()).isEqualTo(CandidateSelectionResult.Status.AMBIGUOUS);
        assertThat(morning.candidates()).extracting(Candidate::businessId)
                .containsExactly(101L, 102L);
        assertThat(service.getOrCreate(CONVERSATION).phase())
                .isEqualTo(AgentPhase.WAITING_SELECTION);

        CandidateSelectionResult second = service.selectCandidate(
                CONVERSATION,
                new CandidateSelector(null, 2, null, null, null, null, null, null));
        assertThat(second.selected().businessId()).isEqualTo(102L);
    }

    @Test
    void appointmentDateAndStatusUseSameSelectorForZeroOneOrManyAndOrdinal() {
        Candidate bookedMorning = appointment(201, "2026-10-12", "上午门诊", "BOOKED");
        Candidate bookedAfternoon = appointment(202, "2026-10-12", "下午门诊", "BOOKED");
        Candidate cancelled = appointment(203, "2026-10-12", "晚间门诊", "CANCELLED");
        CandidateResultSet appointments = service.addResultSet(
                CONVERSATION, "我的预约", CandidateType.APPOINTMENT,
                List.of(bookedMorning, bookedAfternoon, cancelled));

        CandidateSelectionResult severalCancelable = service.selectCandidate(
                CONVERSATION,
                new CandidateSelector(appointments.resultSetId(), null,
                        CandidateType.APPOINTMENT, null, null,
                        "2026-10-12", null, "BOOKED"));
        assertThat(severalCancelable.status())
                .isEqualTo(CandidateSelectionResult.Status.AMBIGUOUS);
        assertThat(severalCancelable.candidates()).extracting(Candidate::businessId)
                .containsExactly(201L, 202L);

        CandidateSelectionResult cancelSecond = service.selectCandidate(
                CONVERSATION,
                new CandidateSelector(null, 2, CandidateType.APPOINTMENT,
                        null, null, null, null, null));
        assertThat(cancelSecond.status()).isEqualTo(CandidateSelectionResult.Status.SELECTED);
        assertThat(cancelSecond.selected().businessId()).isEqualTo(202L);

        CandidateSelectionResult unique = service.selectCandidate(
                CONVERSATION,
                new CandidateSelector(appointments.resultSetId(), null,
                        CandidateType.APPOINTMENT, null, null,
                        "2026-10-12", "下午", "BOOKED"));
        assertThat(unique.selected().businessId()).isEqualTo(202L);

        CandidateSelectionResult none = service.selectCandidate(
                CONVERSATION,
                new CandidateSelector(appointments.resultSetId(), null,
                        CandidateType.APPOINTMENT, "不存在的医生", null,
                        "2026-10-12", null, "BOOKED"));
        assertThat(none.status()).isEqualTo(CandidateSelectionResult.Status.NOT_FOUND);
    }

    @Test
    void isolatesSameConversationUuidByServerScopedUserKey() {
        String otherUser = "patient:9:11111111-1111-4111-8111-111111111111";
        service.addResultSet(CONVERSATION, "用户8", CandidateType.DOCTOR,
                List.of(doctor(11, "张医生")));

        assertThat(service.getCurrentResultSet(otherUser)).isEmpty();
        assertThat(service.getCurrentResultSet(CONVERSATION)).isPresent();
    }

    @Test
    void trimsOnlyOldestResultSets() {
        for (int index = 1; index <= 10; index++) {
            service.addResultSet(CONVERSATION, "结果" + index, CandidateType.DOCTOR,
                    List.of(doctor(index, "医生" + index)));
        }
        assertThat(service.getOrCreate(CONVERSATION).recentResultSets())
                .hasSize(AgentStateService.MAX_RESULT_SETS)
                .extracting(CandidateResultSet::description)
                .containsExactly("结果3", "结果4", "结果5", "结果6",
                        "结果7", "结果8", "结果9", "结果10");
    }

    private Candidate doctor(long id, String name) {
        return new Candidate(id, CandidateType.DOCTOR, name + " 神经内科",
                id, name, 1L, "神经内科", null, null, null, null, null);
    }

    private Candidate slot(long id, String date, String session) {
        return new Candidate(id, CandidateType.SCHEDULE_SLOT,
                "张医生 " + date + " " + session,
                11L, "张医生", 1L, "神经内科", date,
                session.contains("上午") ? "MORNING" : "AFTERNOON",
                session, null, 2);
    }

    private Candidate appointment(long id, String date, String session, String status) {
        return new Candidate(id, CandidateType.APPOINTMENT,
                "张医生 " + date + " " + session + " " + status,
                11L, "张医生", 1L, "神经内科", date,
                null, session, status, null);
    }
}
