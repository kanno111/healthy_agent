package com.healthy.agent.memory.profile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PatientProfile(
        AppointmentPeriod appointmentPeriod,
        ResponseStyle responseStyle,
        Integer age,
        Sex sex,
        Integer heightCm,
        BigDecimal weightKg,
        List<String> conditions,
        List<String> allergies,
        List<String> medications,
        SmokingStatus smokingStatus,
        AlcoholStatus alcoholStatus,
        Instant updatedAt
) {
    public PatientProfile {
        appointmentPeriod = appointmentPeriod == null
                ? AppointmentPeriod.NO_PREFERENCE : appointmentPeriod;
        responseStyle = responseStyle == null ? ResponseStyle.DEFAULT : responseStyle;
        sex = sex == null ? Sex.UNKNOWN : sex;
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        allergies = allergies == null ? List.of() : List.copyOf(allergies);
        medications = medications == null ? List.of() : List.copyOf(medications);
        smokingStatus = smokingStatus == null ? SmokingStatus.UNKNOWN : smokingStatus;
        alcoholStatus = alcoholStatus == null ? AlcoholStatus.UNKNOWN : alcoholStatus;
    }

    public PatientProfile(
            AppointmentPeriod appointmentPeriod,
            ResponseStyle responseStyle,
            Instant updatedAt
    ) {
        this(appointmentPeriod, responseStyle, null, Sex.UNKNOWN, null, null,
                List.of(), List.of(), List.of(), SmokingStatus.UNKNOWN,
                AlcoholStatus.UNKNOWN, updatedAt);
    }

    public static PatientProfile empty() {
        return new PatientProfile(
                AppointmentPeriod.NO_PREFERENCE, ResponseStyle.DEFAULT, null,
                Sex.UNKNOWN, null, null, List.of(), List.of(), List.of(),
                SmokingStatus.UNKNOWN, AlcoholStatus.UNKNOWN, null);
    }
}
