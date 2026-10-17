package com.healthy.agent.memory.profile;

public record ProfileFieldPatch(
        ProfilePatchOperation operation,
        String value
) {
    public ProfileFieldPatch {
        operation = operation == null ? ProfilePatchOperation.KEEP : operation;
        value = value == null ? "" : value.strip();
    }

    public static ProfileFieldPatch keep() {
        return new ProfileFieldPatch(ProfilePatchOperation.KEEP, "");
    }
}
