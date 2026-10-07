package com.healthy.agent.knowledge.index;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.knowledge.DocumentMetadataWriter;
import com.healthy.agent.knowledge.KnowledgeDocumentMetadata;
import com.healthy.agent.knowledge.KnowledgeDocumentParser;
import com.healthy.agent.knowledge.KnowledgeDocumentRepository;
import com.healthy.agent.knowledge.KnowledgeDocumentStorage;
import com.healthy.agent.knowledge.embedding.EmbeddingBatch;
import com.healthy.agent.knowledge.embedding.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class KnowledgeIndexService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexService.class);

    private final KnowledgeDocumentRepository documentRepository;
    private final DocumentMetadataWriter metadataWriter;
    private final KnowledgeDocumentStorage storage;
    private final KnowledgeDocumentParser parser;
    private final DocumentChunker chunker;
    private final EmbeddingClient embeddingClient;
    private final KnowledgeChunkRepository chunkRepository;
    private final Clock clock;

    @Autowired
    public KnowledgeIndexService(
            KnowledgeDocumentRepository documentRepository,
            DocumentMetadataWriter metadataWriter,
            KnowledgeDocumentStorage storage,
            KnowledgeDocumentParser parser,
            DocumentChunker chunker,
            EmbeddingClient embeddingClient,
            KnowledgeChunkRepository chunkRepository
    ) {
        this(documentRepository, metadataWriter, storage, parser, chunker,
                embeddingClient, chunkRepository, Clock.systemUTC());
    }

    KnowledgeIndexService(
            KnowledgeDocumentRepository documentRepository,
            DocumentMetadataWriter metadataWriter,
            KnowledgeDocumentStorage storage,
            KnowledgeDocumentParser parser,
            DocumentChunker chunker,
            EmbeddingClient embeddingClient,
            KnowledgeChunkRepository chunkRepository,
            Clock clock
    ) {
        this.documentRepository = documentRepository;
        this.metadataWriter = metadataWriter;
        this.storage = storage;
        this.parser = parser;
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.chunkRepository = chunkRepository;
        this.clock = clock;
    }

    public synchronized KnowledgeIndexResult build(String documentId, boolean force) {
        KnowledgeDocumentMetadata document = findDocument(documentId);
        if (document.indexed() && !force) {
            return result(document, true);
        }

        markNotIndexed(documentId);
        chunkRepository.deleteByDocumentId(documentId);
        String text = extractText(document);
        List<DocumentChunk> documentChunks = chunker.split(text);
        if (documentChunks.isEmpty()) {
            throw new AgentException(AgentErrorCode.DOCUMENT_TEXT_EMPTY);
        }

        EmbeddingBatch embeddingBatch = embeddingClient.embed(documentChunks.stream()
                .map(DocumentChunk::content)
                .toList());
        if (embeddingBatch.vectors().size() != documentChunks.size()) {
            throw new AgentException(AgentErrorCode.EMBEDDING_UNAVAILABLE);
        }

        Instant indexedAt = clock.instant();
        List<KnowledgeChunk> chunks = java.util.stream.IntStream.range(0, documentChunks.size())
                .mapToObj(index -> {
                    DocumentChunk chunk = documentChunks.get(index);
                    return new KnowledgeChunk(
                        document.id() + "-" + chunk.index(),
                        document.id(),
                        document.originalName(),
                        document.contentType(),
                        chunk.index(),
                        chunk.content(),
                        chunk.content().length(),
                        document.sha256(),
                        embeddingBatch.model(),
                        embeddingBatch.vectors().get(index),
                        indexedAt.toString()
                    );
                })
                .toList();

        chunkRepository.replaceDocumentChunks(documentId, chunks);
        markIndexed(documentId, chunks.size(), indexedAt);
        return new KnowledgeIndexResult(documentId, true, chunks.size(), indexedAt, false);
    }

    private KnowledgeDocumentMetadata findDocument(String documentId) {
        try {
            return documentRepository.findById(documentId)
                    .orElseThrow(() -> new AgentException(AgentErrorCode.DOCUMENT_NOT_FOUND));
        } catch (AgentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("Failed to read document {} before indexing: {}",
                    documentId, exception.getClass().getSimpleName());
            throw new AgentException(AgentErrorCode.METADATA_UNAVAILABLE);
        }
    }

    private String extractText(KnowledgeDocumentMetadata document) {
        try (InputStream input = storage.open(document.objectKey())) {
            return parser.extractText(document, input);
        } catch (AgentException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Failed to read document {} for indexing: {}",
                    document.id(), exception.getClass().getSimpleName());
            throw new AgentException(AgentErrorCode.STORAGE_UNAVAILABLE);
        }
    }

    private void markNotIndexed(String documentId) {
        try {
            metadataWriter.markNotIndexed(documentId, clock.instant());
        } catch (AgentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentException(AgentErrorCode.METADATA_UNAVAILABLE);
        }
    }

    private void markIndexed(String documentId, int chunkCount, Instant indexedAt) {
        try {
            metadataWriter.markIndexed(documentId, chunkCount, indexedAt);
        } catch (AgentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentException(AgentErrorCode.METADATA_UNAVAILABLE);
        }
    }

    private KnowledgeIndexResult result(KnowledgeDocumentMetadata document, boolean skipped) {
        return new KnowledgeIndexResult(
                document.id(), document.indexed(), document.chunkCount(), document.indexedAt(), skipped
        );
    }
}
