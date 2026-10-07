package com.healthy.agent.knowledge;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.knowledge.index.KnowledgeChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class KnowledgeDocumentService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentService.class);
    private static final String DOCUMENT_PREFIX = "documents/";
    private static final String STATUS_UPLOADED = "UPLOADED";
    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("uuuu/MM")
            .withZone(ZoneOffset.UTC);

    private final KnowledgeDocumentStorage storage;
    private final DocumentFileValidator validator;
    private final DocumentMetadataWriter metadataWriter;
    private final KnowledgeDocumentRepository repository;
    private final KnowledgeDocumentParser parser;
    private final KnowledgeChunkRepository chunkRepository;
    private final Clock clock;

    @Autowired
    public KnowledgeDocumentService(
            KnowledgeDocumentStorage storage,
            DocumentFileValidator validator,
            DocumentMetadataWriter metadataWriter,
            KnowledgeDocumentRepository repository,
            KnowledgeDocumentParser parser,
            KnowledgeChunkRepository chunkRepository
    ) {
        this(storage, validator, metadataWriter, repository, parser, chunkRepository, Clock.systemUTC());
    }

    KnowledgeDocumentService(
            KnowledgeDocumentStorage storage,
            DocumentFileValidator validator,
            DocumentMetadataWriter metadataWriter,
            KnowledgeDocumentRepository repository,
            KnowledgeDocumentParser parser,
            KnowledgeChunkRepository chunkRepository,
            Clock clock
    ) {
        this.storage = storage;
        this.validator = validator;
        this.metadataWriter = metadataWriter;
        this.repository = repository;
        this.parser = parser;
        this.chunkRepository = chunkRepository;
        this.clock = clock;
    }

    public KnowledgeDocument upload(MultipartFile file, long uploaderId) {
        ValidatedDocument validated = validator.validate(file);
        Instant uploadedAt = clock.instant();
        String documentId = UUID.randomUUID().toString();
        String objectKey = DOCUMENT_PREFIX + DATE_PATH.format(uploadedAt) + "/"
                + documentId + "/" + validated.fileName();
        String sha256 = calculateSha256(file);

        uploadToStorage(objectKey, file, validated, documentId, sha256, uploaderId);

        KnowledgeDocumentMetadata metadata = new KnowledgeDocumentMetadata(
                documentId,
                validated.fileName(),
                objectKey,
                validated.contentType(),
                file.getSize(),
                sha256,
                uploaderId,
                false,
                0,
                null,
                uploadedAt,
                uploadedAt
        );

        try {
            metadataWriter.save(metadata);
        } catch (RuntimeException exception) {
            log.warn("Failed to save metadata for knowledge document {}: {}",
                    documentId, exception.getClass().getSimpleName());
            compensateStorageUpload(objectKey, documentId);
            throw new AgentException(AgentErrorCode.METADATA_UNAVAILABLE);
        }

        return toResponse(metadata);
    }

    public List<KnowledgeDocument> list() {
        try {
            return repository.findAllNewestFirst().stream()
                    .map(this::toResponse)
                    .toList();
        } catch (RuntimeException exception) {
            log.warn("Failed to list knowledge document metadata: {}",
                    exception.getClass().getSimpleName());
            throw new AgentException(AgentErrorCode.METADATA_UNAVAILABLE);
        }
    }

    public KnowledgeDocumentPreview preview(String documentId) {
        KnowledgeDocumentMetadata document = findMetadata(documentId);
        try (InputStream input = storage.open(document.objectKey())) {
            return parser.parse(document, input);
        } catch (AgentException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Failed to read knowledge document {} from object storage: {}",
                    documentId, exception.getClass().getSimpleName());
            throw new AgentException(AgentErrorCode.STORAGE_UNAVAILABLE);
        }
    }

    public void delete(String documentId) {
        findMetadata(documentId);
        chunkRepository.deleteByDocumentId(documentId);

        KnowledgeDocumentMetadata document;
        try {
            document = metadataWriter.delete(documentId);
        } catch (AgentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("Failed to delete knowledge document metadata {}: {}",
                    documentId, exception.getClass().getSimpleName());
            throw new AgentException(AgentErrorCode.METADATA_UNAVAILABLE);
        }

        try {
            storage.delete(document.objectKey());
        } catch (Exception exception) {
            log.error("Metadata for document {} was deleted but MinIO cleanup failed: {}",
                    documentId, exception.getClass().getSimpleName());
        }
    }

    private KnowledgeDocumentMetadata findMetadata(String documentId) {
        try {
            return repository.findById(documentId)
                    .orElseThrow(() -> new AgentException(AgentErrorCode.DOCUMENT_NOT_FOUND));
        } catch (AgentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("Failed to read knowledge document metadata {}: {}",
                    documentId, exception.getClass().getSimpleName());
            throw new AgentException(AgentErrorCode.METADATA_UNAVAILABLE);
        }
    }

    private void uploadToStorage(
            String objectKey,
            MultipartFile file,
            ValidatedDocument validated,
            String documentId,
            String sha256,
            long uploaderId
    ) {
        try {
            storage.upload(objectKey, file, validated, documentId, sha256, uploaderId);
        } catch (Exception exception) {
            log.warn("Failed to upload knowledge document {} to object storage: {}",
                    documentId, exception.getClass().getSimpleName());
            throw new AgentException(AgentErrorCode.STORAGE_UNAVAILABLE);
        }
    }

    private void compensateStorageUpload(String objectKey, String documentId) {
        try {
            storage.delete(objectKey);
        } catch (Exception cleanupException) {
            log.error("Failed to compensate object storage upload for document {}: {}",
                    documentId, cleanupException.getClass().getSimpleName());
        }
    }

    private String calculateSha256(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = file.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new AgentException(AgentErrorCode.INVALID_FILE);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    private KnowledgeDocument toResponse(KnowledgeDocumentMetadata metadata) {
        return new KnowledgeDocument(
                metadata.id(),
                metadata.originalName(),
                metadata.sizeBytes(),
                metadata.contentType(),
                STATUS_UPLOADED,
                metadata.indexed(),
                metadata.chunkCount(),
                metadata.indexedAt(),
                metadata.createdAt()
        );
    }
}
