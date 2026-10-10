package com.healthy.agent.state;

import com.healthy.agent.action.PendingActionView;
import com.healthy.agent.action.PatientActionResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AgentStateService {
    static final int MAX_RESULT_SETS = 8;
    static final Duration STATE_TTL = Duration.ofMinutes(30);

    private final ConcurrentHashMap<String, AgentState> states = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public AgentStateService() {
        this(Clock.systemUTC());
    }

    public AgentStateService(Clock clock) {
        this.clock = clock;
    }

    public AgentState getOrCreate(String conversationId) {
        return update(conversationId, ignored -> { });
    }

    public CandidateResultSet addResultSet(
            String conversationId,
            String description,
            CandidateType type,
            List<Candidate> candidates
    ) {
        Instant now = clock.instant();
        CandidateResultSet resultSet = new CandidateResultSet(
                "rs_" + java.util.UUID.randomUUID(), description, type, candidates, now);
        update(conversationId, state -> {
            state.mutableResultSets().add(resultSet);
            trimOldResultSets(state);
            state.currentResultSetId(resultSet.resultSetId());
            state.selectedCandidate(null);
        });
        return resultSet;
    }

    public Optional<CandidateResultSet> getCurrentResultSet(String conversationId) {
        AgentState state = getOrCreate(conversationId);
        return findResultSet(state, state.currentResultSetId());
    }

    public Optional<CandidateResultSet> findRecentResultSet(
            String conversationId,
            String resultSetId
    ) {
        AgentState state = getOrCreate(conversationId);
        return findResultSet(state, resultSetId);
    }

    public CandidateSelectionResult selectCandidate(
            String conversationId,
            CandidateSelector selector
    ) {
        AtomicReference<CandidateSelectionResult> result = new AtomicReference<>();
        update(conversationId, state -> {
            CandidateResultSet source = resolveSource(state, selector.resultSetId());
            if (source == null) {
                state.selectedCandidate(null);
                result.set(new CandidateSelectionResult(
                        CandidateSelectionResult.Status.NOT_FOUND, null, null, List.of(),
                        "没有找到可引用的查询结果，请先重新查询。"));
                return;
            }

            List<Candidate> matches = filter(source.candidates(), selector);
            if (selector.position() != null) {
                int index = selector.position() - 1;
                matches = index >= 0 && index < matches.size()
                        ? List.of(matches.get(index)) : List.of();
            }

            if (matches.isEmpty()) {
                state.selectedCandidate(null);
                state.currentResultSetId(source.resultSetId());
                result.set(new CandidateSelectionResult(
                        CandidateSelectionResult.Status.NOT_FOUND, null, source, List.of(),
                        "在“" + source.description() + "”中没有找到符合条件的项目。"));
                return;
            }

            if (matches.size() == 1) {
                Candidate selected = matches.getFirst();
                state.selectedCandidate(selected);
                state.currentResultSetId(source.resultSetId());
                result.set(new CandidateSelectionResult(
                        CandidateSelectionResult.Status.SELECTED, selected, source, matches,
                        "已确定选择：" + selected.displayText()));
                return;
            }

            CandidateResultSet narrowed = source;
            if (matches.size() != source.candidates().size()) {
                narrowed = new CandidateResultSet(
                        "rs_" + java.util.UUID.randomUUID(),
                        source.description() + "的筛选结果",
                        source.type(), matches, clock.instant());
                state.mutableResultSets().add(narrowed);
                trimOldResultSets(state);
            }
            state.currentResultSetId(narrowed.resultSetId());
            state.selectedCandidate(null);
            result.set(new CandidateSelectionResult(
                    CandidateSelectionResult.Status.AMBIGUOUS, null, narrowed, matches,
                    "找到多个符合条件的项目，请继续选择。"));
        });
        return result.get();
    }

    public void setSelectedCandidate(String conversationId, Candidate candidate) {
        update(conversationId, state -> state.selectedCandidate(candidate));
    }

    public Optional<Candidate> selectedCandidate(String conversationId) {
        return Optional.ofNullable(getOrCreate(conversationId).selectedCandidate());
    }

    public Optional<Candidate> findCandidate(
            String conversationId,
            CandidateType type,
            long businessId
    ) {
        AgentState state = getOrCreate(conversationId);
        for (int i = state.recentResultSets().size() - 1; i >= 0; i--) {
            for (Candidate candidate : state.recentResultSets().get(i).candidates()) {
                if (candidate.type() == type && candidate.businessId() == businessId) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    public void prepareAction(
            String conversationId,
            PendingActionView pendingAction
    ) {
        update(conversationId, state -> state.pendingAction(pendingAction));
    }

    public Optional<PendingActionView> getPendingAction(String conversationId) {
        AgentState state = getOrCreate(conversationId);
        PendingActionView pending = state.pendingAction();
        if (pending == null) return Optional.empty();
        if (!clock.instant().isBefore(pending.expiresAt())) {
            clearPendingAction(conversationId, pending.actionId());
            return Optional.empty();
        }
        return Optional.of(pending);
    }

    public void clearPendingAction(String conversationId, String expectedActionId) {
        update(conversationId, state -> {
            if (state.pendingAction() != null
                    && state.pendingAction().actionId().equals(expectedActionId)) {
                state.pendingAction(null);
                state.selectedCandidate(null);
            }
        });
    }

    public void completeAction(
            String conversationId,
            String expectedActionId,
            PatientActionResponse result
    ) {
        if (result == null) throw new IllegalArgumentException("result is required");
        update(conversationId, state -> {
            if (state.pendingAction() != null
                    && state.pendingAction().actionId().equals(expectedActionId)) {
                state.lastActionResult(result);
                state.pendingAction(null);
                state.selectedCandidate(null);
            }
        });
    }

    public Optional<PatientActionResponse> lastActionResult(String conversationId) {
        return Optional.ofNullable(getOrCreate(conversationId).lastActionResult());
    }

    public String promptSummary(String conversationId) {
        AgentState state = getOrCreate(conversationId);
        StringBuilder summary = new StringBuilder();
        summary.append("currentResultSetId=")
                .append(state.currentResultSetId() == null ? "null" : state.currentResultSetId())
                .append('\n');
        if (state.selectedCandidate() != null) {
            summary.append("selectedCandidate={type=")
                    .append(state.selectedCandidate().type())
                    .append(", displayText=")
                    .append(safeSummary(state.selectedCandidate().displayText()))
                    .append("}\n");
        }
        PendingActionView pending = state.pendingAction();
        summary.append("pendingAction=");
        if (pending == null) {
            summary.append("none\n");
        } else {
            summary.append("{type=")
                    .append(pending.type())
                    .append(", status=")
                    .append(pending.status())
                    .append(", expiresAt=")
                    .append(pending.expiresAt())
                    .append("}\n");
        }
        if (state.lastActionResult() != null) {
            PatientActionResponse result = state.lastActionResult();
            summary.append("lastActionResult={type=").append(result.type())
                    .append(", status=").append(result.status())
                    .append(", message=").append(safeSummary(result.message()))
                    .append(", preview=[");
            if (result.preview() != null) {
                result.preview().fields().forEach(field -> summary
                        .append(safeSummary(field.label())).append('=')
                        .append(safeSummary(field.value())).append(';'));
            }
            summary.append("]}\n");
        }
        summary.append("recentResultSets=[\n");
        for (CandidateResultSet set : state.recentResultSets()) {
            summary.append("  {resultSetId=").append(set.resultSetId())
                    .append(", type=").append(set.type())
                    .append(", description=").append(safeSummary(set.description()))
                    .append(", count=").append(set.candidates().size()).append("}\n");
        }
        return summary.append(']').toString();
    }

    /**
     * Provides the intent router with only the business signals needed to understand references.
     * Internal business IDs and result-set IDs deliberately stay out of this summary.
     */
    public String routingSummary(String conversationId) {
        AgentState state = getOrCreate(conversationId);
        StringBuilder summary = new StringBuilder();
        CandidateResultSet current = findResultSet(state, state.currentResultSetId()).orElse(null);
        if (current == null) {
            summary.append("currentResultSet=none\n");
        } else {
            summary.append("currentResultSet={type=").append(current.type())
                    .append(", description=").append(safeSummary(current.description()))
                    .append(", count=").append(current.candidates().size()).append("}\n");
        }

        summary.append("recentResultSets=[");
        int start = Math.max(0, state.recentResultSets().size() - 3);
        for (int index = start; index < state.recentResultSets().size(); index++) {
            CandidateResultSet resultSet = state.recentResultSets().get(index);
            if (index > start) summary.append(", ");
            summary.append("{type=").append(resultSet.type())
                    .append(", description=").append(safeSummary(resultSet.description()))
                    .append(", count=").append(resultSet.candidates().size()).append('}');
        }
        summary.append("]\n");

        Candidate selected = state.selectedCandidate();
        summary.append("selectedCandidate=");
        if (selected == null) {
            summary.append("none\n");
        } else {
            summary.append("{type=").append(selected.type())
                    .append(", displayText=").append(safeSummary(selected.displayText()))
                    .append("}\n");
        }

        PendingActionView pending = state.pendingAction();
        summary.append("pendingAction=");
        if (pending == null) {
            summary.append("none\n");
        } else {
            summary.append("{type=").append(pending.type())
                    .append(", status=").append(pending.status()).append("}\n");
        }

        PatientActionResponse last = state.lastActionResult();
        summary.append("lastActionResult=");
        if (last == null) {
            summary.append("none");
        } else {
            summary.append("{type=").append(last.type())
                    .append(", status=").append(last.status()).append('}');
        }
        return summary.toString();
    }

    private AgentState update(String conversationId, java.util.function.Consumer<AgentState> change) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId is required");
        }
        Instant now = clock.instant();
        AgentState updated = states.compute(conversationId, (ignored, current) -> {
            AgentState state = current == null || !now.isBefore(current.expiresAt())
                    ? new AgentState(conversationId, now.plus(STATE_TTL)) : current;
            change.accept(state);
            state.expiresAt(now.plus(STATE_TTL));
            return state;
        });
        return updated.copy();
    }

    private CandidateResultSet resolveSource(AgentState state, String requestedId) {
        String id = requestedId == null || requestedId.isBlank()
                ? state.currentResultSetId() : requestedId.strip();
        return findResultSet(state, id).orElse(null);
    }

    private Optional<CandidateResultSet> findResultSet(AgentState state, String id) {
        if (id == null) return Optional.empty();
        return state.recentResultSets().stream()
                .filter(result -> result.resultSetId().equals(id))
                .findFirst();
    }

    private List<Candidate> filter(List<Candidate> source, CandidateSelector selector) {
        List<Candidate> matches = new ArrayList<>();
        for (Candidate candidate : source) {
            if (selector.type() != null && candidate.type() != selector.type()) continue;
            if (!contains(candidate.doctorName(), selector.doctorName())) continue;
            if (!contains(candidate.departmentName(), selector.departmentName())) continue;
            if (!equalsText(candidate.date(), selector.date())) continue;
            if (!contains(sessionText(candidate), selector.sessionName())) continue;
            if (!equalsText(candidate.status(), selector.status())) continue;
            matches.add(candidate);
        }
        return matches;
    }

    private String sessionText(Candidate candidate) {
        return String.join(" ", nullToEmpty(candidate.sessionType()),
                nullToEmpty(candidate.sessionName()));
    }

    private boolean contains(String actual, String expected) {
        if (expected == null || expected.isBlank()) return true;
        if (actual == null) return false;
        return actual.toLowerCase(Locale.ROOT)
                .contains(expected.strip().toLowerCase(Locale.ROOT));
    }

    private boolean equalsText(String actual, String expected) {
        if (expected == null || expected.isBlank()) return true;
        return actual != null && actual.equalsIgnoreCase(expected.strip());
    }

    private void trimOldResultSets(AgentState state) {
        while (state.mutableResultSets().size() > MAX_RESULT_SETS) {
            CandidateResultSet removed = state.mutableResultSets().removeFirst();
            if (removed.resultSetId().equals(state.currentResultSetId())) {
                state.currentResultSetId(null);
            }
        }
    }

    private String safeSummary(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
