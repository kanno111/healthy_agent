package com.healthy.agent.memory.profile;

import com.healthy.agent.llm.TokenUsage;

public record PatientProfileExtractionResult(
        PatientProfilePatch patch,
        TokenUsage usage
) {
    public PatientProfileExtractionResult {
        patch = patch == null ? PatientProfilePatch.keepAll() : patch;
        usage = usage == null ? TokenUsage.empty() : usage;
    }

    public static PatientProfileExtractionResult empty() {
        return new PatientProfileExtractionResult(
                PatientProfilePatch.keepAll(), TokenUsage.empty());
    }
}
