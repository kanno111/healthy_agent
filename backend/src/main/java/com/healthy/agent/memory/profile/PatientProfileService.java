package com.healthy.agent.memory.profile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class PatientProfileService {
    static final int MAX_COLLECTION_ITEMS = 10;
    static final int MAX_COLLECTION_ITEM_LENGTH = 50;
    private static final int MIN_AGE = 0;
    private static final int MAX_AGE = 150;
    private static final int MIN_HEIGHT_CM = 30;
    private static final int MAX_HEIGHT_CM = 250;
    private static final BigDecimal MIN_WEIGHT_KG = new BigDecimal("1");
    private static final BigDecimal MAX_WEIGHT_KG = new BigDecimal("500");
    private static final Pattern SAFE_PROFILE_ITEM = Pattern.compile(
            "[\\p{L}\\p{N}\\s（）()·+\\-./%]+"
    );
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final Logger log = LoggerFactory.getLogger(PatientProfileService.class);
    private static final String PREFIX = "healthy-agent:memory:profile:patient:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public PatientProfileService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this(redis, objectMapper, Clock.systemUTC());
    }

    PatientProfileService(StringRedisTemplate redis, ObjectMapper objectMapper, Clock clock) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public PatientProfile get(long userId) {
        try {
            Map<Object, Object> values = redis.opsForHash().entries(key(userId));
            if (values == null || values.isEmpty()) return PatientProfile.empty();
            return new PatientProfile(
                    enumValue(values.get("appointmentPeriod"), AppointmentPeriod.class,
                            AppointmentPeriod.NO_PREFERENCE),
                    enumValue(values.get("responseStyle"), ResponseStyle.class,
                            ResponseStyle.DEFAULT),
                    integerValue(values.get("age"), MIN_AGE, MAX_AGE),
                    enumValue(values.get("sex"), Sex.class, Sex.UNKNOWN),
                    integerValue(values.get("heightCm"), MIN_HEIGHT_CM, MAX_HEIGHT_CM),
                    decimalValue(values.get("weightKg"), MIN_WEIGHT_KG, MAX_WEIGHT_KG),
                    listValue(values.get("conditions")),
                    listValue(values.get("allergies")),
                    listValue(values.get("medications")),
                    enumValue(values.get("smokingStatus"), SmokingStatus.class,
                            SmokingStatus.UNKNOWN),
                    enumValue(values.get("alcoholStatus"), AlcoholStatus.class,
                            AlcoholStatus.UNKNOWN),
                    instantValue(values.get("updatedAt")));
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    public PatientProfile getOrEmpty(long userId) {
        try {
            return get(userId);
        } catch (RuntimeException exception) {
            log.warn("Patient profile read failed: {}", exception.getClass().getSimpleName());
            return PatientProfile.empty();
        }
    }

    public PatientProfile replace(long userId, PatientProfileUpdateRequest request) {
        if (request == null || request.appointmentPeriod() == null
                || request.responseStyle() == null || request.sex() == null
                || request.conditions() == null || request.allergies() == null
                || request.medications() == null || request.smokingStatus() == null
                || request.alcoholStatus() == null) {
            throw invalid();
        }
        return save(userId, normalizedProfile(request));
    }

    public PatientProfile applyPatch(long userId, PatientProfilePatch patch) {
        if (patch == null || !patch.changesAnything()) return getOrEmpty(userId);
        PatientProfile current = get(userId);
        Map<String, String> updates = new LinkedHashMap<>();
        patchEnum(updates, "appointmentPeriod", patch.appointmentPeriod(),
                AppointmentPeriod.class, AppointmentPeriod.NO_PREFERENCE);
        patchEnum(updates, "responseStyle", patch.responseStyle(),
                ResponseStyle.class, ResponseStyle.DEFAULT);
        patchInteger(updates, "age", patch.age(), MIN_AGE, MAX_AGE);
        patchEnum(updates, "sex", patch.sex(), Sex.class, Sex.UNKNOWN);
        patchInteger(updates, "heightCm", patch.heightCm(), MIN_HEIGHT_CM, MAX_HEIGHT_CM);
        patchDecimal(updates, "weightKg", patch.weightKg(), MIN_WEIGHT_KG, MAX_WEIGHT_KG);
        patchCollection(updates, "conditions", current.conditions(), patch.conditions());
        patchCollection(updates, "allergies", current.allergies(), patch.allergies());
        patchCollection(updates, "medications", current.medications(), patch.medications());
        patchEnum(updates, "smokingStatus", patch.smokingStatus(),
                SmokingStatus.class, SmokingStatus.UNKNOWN);
        patchEnum(updates, "alcoholStatus", patch.alcoholStatus(),
                AlcoholStatus.class, AlcoholStatus.UNKNOWN);
        updates.put("updatedAt", clock.instant().toString());
        try {
            redis.opsForHash().putAll(key(userId), updates);
            return get(userId);
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    public void delete(long userId) {
        try {
            redis.delete(key(userId));
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    public String promptSummary(long userId) {
        PatientProfile profile = getOrEmpty(userId);
        List<String> parts = new ArrayList<>();
        switch (profile.appointmentPeriod()) {
            case MORNING -> parts.add("预约时段偏好：上午");
            case AFTERNOON -> parts.add("预约时段偏好：下午");
            case NO_PREFERENCE -> { }
        }
        switch (profile.responseStyle()) {
            case CONCISE -> parts.add("回答风格：简洁");
            case DETAILED -> parts.add("回答风格：详细");
            case DEFAULT -> { }
        }
        if (profile.age() != null) parts.add("用户自述年龄：" + profile.age() + "岁");
        switch (profile.sex()) {
            case MALE -> parts.add("用户自述生理性别：男");
            case FEMALE -> parts.add("用户自述生理性别：女");
            case UNKNOWN -> { }
        }
        if (profile.heightCm() != null) {
            parts.add("用户自述身高：" + profile.heightCm() + "厘米");
        }
        if (profile.weightKg() != null) {
            parts.add("用户自述体重：" + displayDecimal(profile.weightKg()) + "千克");
        }
        addList(parts, "用户自述已确诊疾病/慢性病", profile.conditions());
        addList(parts, "用户自述过敏史", profile.allergies());
        addList(parts, "用户自述当前用药", profile.medications());
        switch (profile.smokingStatus()) {
            case NEVER -> parts.add("用户自述吸烟情况：从不吸烟");
            case FORMER -> parts.add("用户自述吸烟情况：已戒烟");
            case CURRENT -> parts.add("用户自述吸烟情况：目前吸烟");
            case UNKNOWN -> { }
        }
        switch (profile.alcoholStatus()) {
            case NEVER -> parts.add("用户自述饮酒情况：从不饮酒");
            case FORMER -> parts.add("用户自述饮酒情况：已戒酒");
            case CURRENT -> parts.add("用户自述饮酒情况：目前饮酒");
            case UNKNOWN -> { }
        }
        if (parts.isEmpty()) return "无已保存画像";
        return "以下内容包含用户自述健康信息，未经医院确认，不是病历或实时医疗事实：\n- "
                + String.join("\n- ", parts);
    }

    private PatientProfile normalizedProfile(PatientProfileUpdateRequest request) {
        return new PatientProfile(
                request.appointmentPeriod(),
                request.responseStyle(),
                validatedInteger(request.age(), MIN_AGE, MAX_AGE),
                request.sex(),
                validatedInteger(request.heightCm(), MIN_HEIGHT_CM, MAX_HEIGHT_CM),
                validatedDecimal(request.weightKg(), MIN_WEIGHT_KG, MAX_WEIGHT_KG),
                normalizeList(request.conditions()),
                normalizeList(request.allergies()),
                normalizeList(request.medications()),
                request.smokingStatus(),
                request.alcoholStatus(),
                null);
    }

    private PatientProfile save(long userId, PatientProfile profile) {
        Instant now = clock.instant();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("appointmentPeriod", profile.appointmentPeriod().name());
        values.put("responseStyle", profile.responseStyle().name());
        values.put("age", stringValue(profile.age()));
        values.put("sex", profile.sex().name());
        values.put("heightCm", stringValue(profile.heightCm()));
        values.put("weightKg", profile.weightKg() == null
                ? "" : displayDecimal(profile.weightKg()));
        values.put("conditions", jsonList(profile.conditions()));
        values.put("allergies", jsonList(profile.allergies()));
        values.put("medications", jsonList(profile.medications()));
        values.put("smokingStatus", profile.smokingStatus().name());
        values.put("alcoholStatus", profile.alcoholStatus().name());
        values.put("updatedAt", now.toString());
        try {
            redis.opsForHash().putAll(key(userId), values);
            return new PatientProfile(
                    profile.appointmentPeriod(), profile.responseStyle(), profile.age(),
                    profile.sex(), profile.heightCm(), profile.weightKg(), profile.conditions(),
                    profile.allergies(), profile.medications(), profile.smokingStatus(),
                    profile.alcoholStatus(), now);
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    private <T extends Enum<T>> void patchEnum(
            Map<String, String> updates,
            String field,
            ProfileFieldPatch patch,
            Class<T> type,
            T cleared
    ) {
        String value = switch (patch.operation()) {
            case KEEP -> null;
            case CLEAR -> cleared.name();
            case SET -> {
                try {
                    yield Enum.valueOf(type, patch.value().toUpperCase(Locale.ROOT)).name();
                } catch (IllegalArgumentException exception) {
                    throw invalid();
                }
            }
            case ADD, REMOVE -> throw invalid();
        };
        if (value != null) updates.put(field, value);
    }

    private void patchInteger(
            Map<String, String> updates,
            String field,
            ProfileFieldPatch patch,
            int min,
            int max
    ) {
        String value = switch (patch.operation()) {
            case KEEP -> null;
            case CLEAR -> "";
            case SET -> {
                try {
                    yield Integer.toString(validatedInteger(
                            Integer.valueOf(patch.value()), min, max));
                } catch (NumberFormatException exception) {
                    throw invalid();
                }
            }
            case ADD, REMOVE -> throw invalid();
        };
        if (value != null) updates.put(field, value);
    }

    private void patchDecimal(
            Map<String, String> updates,
            String field,
            ProfileFieldPatch patch,
            BigDecimal min,
            BigDecimal max
    ) {
        String value = switch (patch.operation()) {
            case KEEP -> null;
            case CLEAR -> "";
            case SET -> {
                try {
                    yield displayDecimal(validatedDecimal(
                            new BigDecimal(patch.value()), min, max));
                } catch (NumberFormatException exception) {
                    throw invalid();
                }
            }
            case ADD, REMOVE -> throw invalid();
        };
        if (value != null) updates.put(field, value);
    }

    private void patchCollection(
            Map<String, String> updates,
            String field,
            List<String> current,
            ProfileCollectionPatch patch
    ) {
        List<String> values = switch (patch.operation()) {
            case KEEP -> null;
            case CLEAR -> List.of();
            case ADD -> merge(current, normalizeList(patch.values()), true);
            case REMOVE -> merge(current, normalizeList(patch.values()), false);
            case SET -> throw invalid();
        };
        if (values != null) updates.put(field, jsonList(values));
    }

    private List<String> merge(List<String> current, List<String> changes, boolean add) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        for (String value : current) merged.put(value.toLowerCase(Locale.ROOT), value);
        for (String value : changes) {
            String key = value.toLowerCase(Locale.ROOT);
            if (add) merged.putIfAbsent(key, value);
            else merged.remove(key);
        }
        if (merged.size() > MAX_COLLECTION_ITEMS) throw invalid();
        return List.copyOf(merged.values());
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.size() > MAX_COLLECTION_ITEMS) throw invalid();
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (String raw : values) {
            if (raw == null) throw invalid();
            String value = raw.strip().replaceAll("\\s+", " ");
            if (value.isEmpty() || value.length() > MAX_COLLECTION_ITEM_LENGTH
                    || !SAFE_PROFILE_ITEM.matcher(value).matches()) {
                throw invalid();
            }
            normalized.putIfAbsent(value.toLowerCase(Locale.ROOT), value);
        }
        return List.copyOf(normalized.values());
    }

    private Integer validatedInteger(Integer value, int min, int max) {
        if (value == null) return null;
        if (value < min || value > max) throw invalid();
        return value;
    }

    private BigDecimal validatedDecimal(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value == null) return null;
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.compareTo(min) < 0 || normalized.compareTo(max) > 0
                || normalized.scale() > 2) {
            throw invalid();
        }
        return normalized;
    }

    private Integer integerValue(Object raw, int min, int max) {
        if (raw == null || raw.toString().isBlank()) return null;
        try {
            return validatedInteger(Integer.valueOf(raw.toString()), min, max);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private BigDecimal decimalValue(Object raw, BigDecimal min, BigDecimal max) {
        if (raw == null || raw.toString().isBlank()) return null;
        try {
            return validatedDecimal(new BigDecimal(raw.toString()), min, max);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private List<String> listValue(Object raw) {
        if (raw == null || raw.toString().isBlank()) return List.of();
        try {
            return normalizeList(objectMapper.readValue(raw.toString(), STRING_LIST));
        } catch (RuntimeException | JsonProcessingException exception) {
            return List.of();
        }
    }

    private <T extends Enum<T>> T enumValue(Object raw, Class<T> type, T fallback) {
        if (raw == null) return fallback;
        try {
            return Enum.valueOf(type, raw.toString());
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private Instant instantValue(Object raw) {
        if (raw == null) return null;
        try {
            return Instant.parse(raw.toString());
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private String jsonList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw invalid();
        }
    }

    private void addList(List<String> parts, String label, List<String> values) {
        if (!values.isEmpty()) parts.add(label + "：" + String.join("、", values));
    }

    private String displayDecimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private String key(long userId) {
        return PREFIX + userId;
    }

    private AgentException invalid() {
        return new AgentException(AgentErrorCode.INVALID_PATIENT_PROFILE);
    }

    private AgentException unavailable() {
        return new AgentException(AgentErrorCode.CHAT_HISTORY_UNAVAILABLE);
    }
}
