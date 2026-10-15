package com.healthy.agent.trace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Request-scoped, best-effort trace recorder. It never stores prompts, credentials or tool arguments. */
public final class AgentTraceRecorder {
    private static final int MAX_TEXT_LENGTH = 240;
    private static final Set<String> FORBIDDEN_DETAIL_KEYS = Set.of(
            "authorization", "password", "prompt", "completion",
            "toolarguments", "toolresult", "userid", "patientid",
            "appointmentid", "scheduleslotid", "doctorid", "departmentid");
    private static final AgentTraceRecorder NOOP = new AgentTraceRecorder(false);

    private final boolean enabled;
    private final String turnId;
    private final Instant startedAt;
    private final long startedNanos;
    private final AtomicInteger sequence = new AtomicInteger();
    private final List<AgentTraceStep> steps = new CopyOnWriteArrayList<>();
    private volatile long completedNanos;

    private AgentTraceRecorder(boolean enabled) {
        this.enabled = enabled;
        this.turnId = enabled ? UUID.randomUUID().toString() : "";
        this.startedAt = Instant.now();
        this.startedNanos = System.nanoTime();
    }

    public static AgentTraceRecorder create() {
        return new AgentTraceRecorder(true);
    }

    public static AgentTraceRecorder noop() {
        return NOOP;
    }

    public String turnId() {
        return turnId;
    }

    public StepHandle start(AgentTraceStepType type, String name) {
        if (!enabled) return StepHandle.noop();
        try {
            return new StepHandle(
                    this,
                    sequence.incrementAndGet(),
                    type == null ? AgentTraceStepType.TOOL : type,
                    safeText(name),
                    Instant.now(),
                    System.nanoTime(),
                    true);
        } catch (RuntimeException ignored) {
            return StepHandle.noop();
        }
    }

    public void instant(
            AgentTraceStepType type,
            String name,
            AgentTraceStatus status,
            String summary,
            Map<String, Object> details
    ) {
        StepHandle handle = start(type, name);
        if (status == AgentTraceStatus.FAILED) handle.failure(summary, details);
        else handle.success(summary, details);
    }

    public void complete() {
        if (enabled && completedNanos == 0L) completedNanos = System.nanoTime();
    }

    public AgentTrace snapshot() {
        try {
            long end = completedNanos == 0L ? System.nanoTime() : completedNanos;
            List<AgentTraceStep> ordered = new ArrayList<>(steps);
            ordered.sort(Comparator.comparingInt(AgentTraceStep::sequence));
            return new AgentTrace(
                    turnId,
                    startedAt,
                    Math.max(0L, (end - startedNanos) / 1_000_000L),
                    ordered);
        } catch (RuntimeException ignored) {
            return new AgentTrace(turnId, startedAt, 0L, List.of());
        }
    }

    private void add(
            int sequence,
            AgentTraceStepType type,
            String name,
            Instant stepStartedAt,
            long stepStartedNanos,
            AgentTraceStatus status,
            String summary,
            Map<String, Object> details
    ) {
        if (!enabled) return;
        try {
            steps.add(new AgentTraceStep(
                    sequence,
                    type,
                    name,
                    stepStartedAt,
                    Math.max(0L, (System.nanoTime() - stepStartedNanos) / 1_000_000L),
                    status,
                    safeText(summary),
                    safeDetails(details)));
        } catch (RuntimeException ignored) {
            // Trace is diagnostic only and must never fail the patient request.
        }
    }

    private static Map<String, Object> safeDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) return Map.of();
        return safeMap(details);
    }

    private static Map<String, Object> safeMap(Map<?, ?> values) {
        Map<String, Object> safe = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            String keyText = key == null ? "" : key.toString();
            if (!keyText.isBlank() && value != null
                    && !FORBIDDEN_DETAIL_KEYS.contains(
                            keyText.toLowerCase(java.util.Locale.ROOT))) {
                safe.put(safeText(keyText), safeValue(value));
            }
        });
        return Map.copyOf(safe);
    }

    private static Object safeValue(Object value) {
        if (value instanceof String text) return safeText(text);
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) return value;
        if (value instanceof List<?> values) {
            return values.stream().limit(20).map(AgentTraceRecorder::safeValue).toList();
        }
        if (value instanceof Map<?, ?> values) {
            return safeMap(values);
        }
        return safeText(value.toString());
    }

    private static String safeText(String value) {
        if (value == null) return "";
        String normalized = value.replace('\r', ' ').replace('\n', ' ').strip();
        if (normalized.regionMatches(true, 0, "Bearer ", 0, 7)) return "[REDACTED]";
        return normalized.length() <= MAX_TEXT_LENGTH
                ? normalized : normalized.substring(0, MAX_TEXT_LENGTH);
    }

    public static final class StepHandle {
        private static final StepHandle NOOP = new StepHandle(
                null, 0, AgentTraceStepType.TOOL, "", Instant.EPOCH, 0L, false);

        private final AgentTraceRecorder recorder;
        private final int sequence;
        private final AgentTraceStepType type;
        private final String name;
        private final Instant startedAt;
        private final long startedNanos;
        private final boolean enabled;
        private final AtomicBoolean finished = new AtomicBoolean();

        private StepHandle(
                AgentTraceRecorder recorder,
                int sequence,
                AgentTraceStepType type,
                String name,
                Instant startedAt,
                long startedNanos,
                boolean enabled
        ) {
            this.recorder = recorder;
            this.sequence = sequence;
            this.type = type;
            this.name = name;
            this.startedAt = startedAt;
            this.startedNanos = startedNanos;
            this.enabled = enabled;
        }

        private static StepHandle noop() {
            return NOOP;
        }

        public void success(String summary) {
            success(summary, Map.of());
        }

        public void success(String summary, Map<String, Object> details) {
            finish(AgentTraceStatus.SUCCESS, summary, details);
        }

        public void failure(String summary) {
            failure(summary, Map.of());
        }

        public void failure(String summary, Map<String, Object> details) {
            finish(AgentTraceStatus.FAILED, summary, details);
        }

        private void finish(
                AgentTraceStatus status,
                String summary,
                Map<String, Object> details
        ) {
            if (!enabled || !finished.compareAndSet(false, true)) return;
            recorder.add(sequence, type, name, startedAt, startedNanos,
                    status, summary, details);
        }
    }
}
