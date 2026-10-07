package com.healthy.agent.knowledge;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class DocumentMetadataWriter {
    private final KnowledgeDocumentRepository repository;

    public DocumentMetadataWriter(KnowledgeDocumentRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void save(KnowledgeDocumentMetadata document) {
        repository.insert(document);
    }

    @Transactional
    public KnowledgeDocumentMetadata delete(String documentId) {
        KnowledgeDocumentMetadata document = repository.findById(documentId)
                .orElseThrow(() -> new AgentException(AgentErrorCode.DOCUMENT_NOT_FOUND));
        if (repository.deleteById(documentId) != 1) {
            throw new AgentException(AgentErrorCode.DOCUMENT_NOT_FOUND);
        }
        return document;
    }

    @Transactional
    public void markNotIndexed(String documentId, Instant updatedAt) {
        requireUpdated(repository.markNotIndexed(documentId, updatedAt));
    }

    @Transactional
    public void markIndexed(String documentId, int chunkCount, Instant indexedAt) {
        requireUpdated(repository.markIndexed(documentId, chunkCount, indexedAt));
    }

    private void requireUpdated(int rows) {
        if (rows != 1) {
            throw new AgentException(AgentErrorCode.DOCUMENT_NOT_FOUND);
        }
    }
}
