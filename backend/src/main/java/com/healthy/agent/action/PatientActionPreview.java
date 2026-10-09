package com.healthy.agent.action;

import java.util.List;

public record PatientActionPreview(
        String title,
        String description,
        String confirmButtonText,
        String rejectButtonText,
        List<ActionPreviewField> fields
) {
    public PatientActionPreview {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
