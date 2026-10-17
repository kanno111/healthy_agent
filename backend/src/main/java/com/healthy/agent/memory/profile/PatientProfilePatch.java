package com.healthy.agent.memory.profile;

public record PatientProfilePatch(
        ProfileFieldPatch appointmentPeriod,
        ProfileFieldPatch responseStyle,
        ProfileFieldPatch age,
        ProfileFieldPatch sex,
        ProfileFieldPatch heightCm,
        ProfileFieldPatch weightKg,
        ProfileCollectionPatch conditions,
        ProfileCollectionPatch allergies,
        ProfileCollectionPatch medications,
        ProfileFieldPatch smokingStatus,
        ProfileFieldPatch alcoholStatus
) {
    public PatientProfilePatch {
        appointmentPeriod = appointmentPeriod == null
                ? ProfileFieldPatch.keep() : appointmentPeriod;
        responseStyle = responseStyle == null ? ProfileFieldPatch.keep() : responseStyle;
        age = age == null ? ProfileFieldPatch.keep() : age;
        sex = sex == null ? ProfileFieldPatch.keep() : sex;
        heightCm = heightCm == null ? ProfileFieldPatch.keep() : heightCm;
        weightKg = weightKg == null ? ProfileFieldPatch.keep() : weightKg;
        conditions = conditions == null ? ProfileCollectionPatch.keep() : conditions;
        allergies = allergies == null ? ProfileCollectionPatch.keep() : allergies;
        medications = medications == null ? ProfileCollectionPatch.keep() : medications;
        smokingStatus = smokingStatus == null ? ProfileFieldPatch.keep() : smokingStatus;
        alcoholStatus = alcoholStatus == null ? ProfileFieldPatch.keep() : alcoholStatus;
    }

    public PatientProfilePatch(
            ProfileFieldPatch appointmentPeriod,
            ProfileFieldPatch responseStyle
    ) {
        this(appointmentPeriod, responseStyle,
                ProfileFieldPatch.keep(), ProfileFieldPatch.keep(),
                ProfileFieldPatch.keep(), ProfileFieldPatch.keep(),
                ProfileCollectionPatch.keep(), ProfileCollectionPatch.keep(),
                ProfileCollectionPatch.keep(), ProfileFieldPatch.keep(),
                ProfileFieldPatch.keep());
    }

    public static PatientProfilePatch keepAll() {
        return new PatientProfilePatch(ProfileFieldPatch.keep(), ProfileFieldPatch.keep());
    }

    public boolean changesAnything() {
        return appointmentPeriod.operation() != ProfilePatchOperation.KEEP
                || responseStyle.operation() != ProfilePatchOperation.KEEP
                || age.operation() != ProfilePatchOperation.KEEP
                || sex.operation() != ProfilePatchOperation.KEEP
                || heightCm.operation() != ProfilePatchOperation.KEEP
                || weightKg.operation() != ProfilePatchOperation.KEEP
                || conditions.operation() != ProfilePatchOperation.KEEP
                || allergies.operation() != ProfilePatchOperation.KEEP
                || medications.operation() != ProfilePatchOperation.KEEP
                || smokingStatus.operation() != ProfilePatchOperation.KEEP
                || alcoholStatus.operation() != ProfilePatchOperation.KEEP;
    }
}
