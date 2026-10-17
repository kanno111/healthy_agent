package com.healthy.agent.memory.profile;

import java.util.List;

public record ProfileCollectionPatch(
        ProfilePatchOperation operation,
        List<String> values
) {
    public ProfileCollectionPatch {
        operation = operation == null ? ProfilePatchOperation.KEEP : operation;
        values = values == null ? List.of() : List.copyOf(values);
    }

    public static ProfileCollectionPatch keep() {
        return new ProfileCollectionPatch(ProfilePatchOperation.KEEP, List.of());
    }
}
