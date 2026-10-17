package com.healthy.agent.memory.profile;

import java.math.BigDecimal;
import java.util.List;

public record PatientProfileUpdateRequest(
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
        AlcoholStatus alcoholStatus
) { }
