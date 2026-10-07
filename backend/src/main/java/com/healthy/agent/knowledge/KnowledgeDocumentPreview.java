package com.healthy.agent.knowledge;

public record KnowledgeDocumentPreview(
        String documentId,
        String fileName,
        String contentType,
        String preview,
        long extractedCharacters,
        int previewCharacters,
        boolean truncated
) {
}
