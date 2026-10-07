package com.healthy.agent.knowledge;

import com.healthy.agent.common.AgentErrorCode;
import com.healthy.agent.common.AgentException;
import com.healthy.agent.knowledge.index.KnowledgeChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeDocumentServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-06T14:00:00Z");

    private KnowledgeDocumentStorage storage;
    private DocumentMetadataWriter metadataWriter;
    private KnowledgeDocumentRepository repository;
    private KnowledgeDocumentParser parser;
    private KnowledgeChunkRepository chunkRepository;
    private KnowledgeDocumentService service;

    @BeforeEach
    void setUp() {
        storage = mock(KnowledgeDocumentStorage.class);
        metadataWriter = mock(DocumentMetadataWriter.class);
        repository = mock(KnowledgeDocumentRepository.class);
        parser = mock(KnowledgeDocumentParser.class);
        chunkRepository = mock(KnowledgeChunkRepository.class);
        service = new KnowledgeDocumentService(
                storage,
                new DocumentFileValidator(20 * 1024 * 1024),
                metadataWriter,
                repository,
                parser,
                chunkRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void uploadsObjectBeforeSavingSuccessfulMetadata() throws Exception {
        MockMultipartFile file = markdownFile();

        KnowledgeDocument uploaded = service.upload(file, 12L);

        ArgumentCaptor<KnowledgeDocumentMetadata> metadataCaptor =
                ArgumentCaptor.forClass(KnowledgeDocumentMetadata.class);
        verify(metadataWriter).save(metadataCaptor.capture());
        KnowledgeDocumentMetadata metadata = metadataCaptor.getValue();
        verify(storage).upload(
                metadata.objectKey(), file, new ValidatedDocument(
                        "制度.md", "md", "text/markdown; charset=utf-8"
                ), metadata.id(), metadata.sha256(), 12L
        );
        assertThat(metadata.objectKey())
                .isEqualTo("documents/2026/10/" + metadata.id() + "/制度.md");
        assertThat(metadata.sha256()).hasSize(64);
        assertThat(metadata.createdAt()).isEqualTo(NOW);
        assertThat(uploaded.id()).isEqualTo(metadata.id());
        assertThat(uploaded.status()).isEqualTo("UPLOADED");
    }

    @Test
    void deletesUploadedObjectWhenMetadataInsertFails() throws Exception {
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(metadataWriter).save(any());

        assertThatThrownBy(() -> service.upload(markdownFile(), 12L))
                .isInstanceOfSatisfying(AgentException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(AgentErrorCode.METADATA_UNAVAILABLE));

        ArgumentCaptor<String> objectKey = ArgumentCaptor.forClass(String.class);
        verify(storage).delete(objectKey.capture());
        assertThat(objectKey.getValue()).startsWith("documents/2026/10/").endsWith("/制度.md");
    }

    @Test
    void doesNotWriteMetadataWhenObjectUploadFails() throws Exception {
        doThrow(new IOException("storage unavailable"))
                .when(storage).upload(anyString(), any(), any(), anyString(), anyString(), anyLong());

        assertThatThrownBy(() -> service.upload(markdownFile(), 12L))
                .isInstanceOfSatisfying(AgentException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(AgentErrorCode.STORAGE_UNAVAILABLE));

        verifyNoInteractions(metadataWriter);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void listsOnlyMetadataPersistedInMysql() {
        KnowledgeDocumentMetadata metadata = metadata();
        when(repository.findAllNewestFirst()).thenReturn(List.of(metadata));

        List<KnowledgeDocument> documents = service.list();

        assertThat(documents).containsExactly(new KnowledgeDocument(
                "document-id", "制度.md", 12,
                "text/markdown; charset=utf-8", "UPLOADED",
                false, 0, null, NOW
        ));
    }

    @Test
    void readsObjectAndReturnsParsedPreview() throws Exception {
        KnowledgeDocumentMetadata metadata = metadata();
        ByteArrayInputStream input = new ByteArrayInputStream("# 制度".getBytes(StandardCharsets.UTF_8));
        KnowledgeDocumentPreview expected = new KnowledgeDocumentPreview(
                metadata.id(), metadata.originalName(), metadata.contentType(),
                "制度", 2, 2, false
        );
        when(repository.findById(metadata.id())).thenReturn(java.util.Optional.of(metadata));
        when(storage.open(metadata.objectKey())).thenReturn(input);
        when(parser.parse(metadata, input)).thenReturn(expected);

        assertThat(service.preview(metadata.id())).isEqualTo(expected);
        verify(storage).open(metadata.objectKey());
    }

    @Test
    void deletesMetadataThenCleansUpStoredObject() throws Exception {
        KnowledgeDocumentMetadata metadata = metadata();
        when(metadataWriter.delete(metadata.id())).thenReturn(metadata);
        when(repository.findById(metadata.id())).thenReturn(java.util.Optional.of(metadata));

        service.delete(metadata.id());

        verify(metadataWriter).delete(metadata.id());
        verify(chunkRepository).deleteByDocumentId(metadata.id());
        verify(storage).delete(metadata.objectKey());
    }

    @Test
    void keepsDeleteSuccessfulWhenMinioCleanupFails() throws Exception {
        KnowledgeDocumentMetadata metadata = metadata();
        when(metadataWriter.delete(metadata.id())).thenReturn(metadata);
        when(repository.findById(metadata.id())).thenReturn(java.util.Optional.of(metadata));
        doThrow(new IOException("minio unavailable")).when(storage).delete(metadata.objectKey());

        service.delete(metadata.id());

        verify(metadataWriter).delete(metadata.id());
    }

    private KnowledgeDocumentMetadata metadata() {
        return new KnowledgeDocumentMetadata(
                "document-id",
                "制度.md",
                "documents/2026/10/document-id/制度.md",
                "text/markdown; charset=utf-8",
                12,
                "a".repeat(64),
                12,
                false,
                0,
                null,
                NOW,
                NOW
        );
    }

    private MockMultipartFile markdownFile() {
        return new MockMultipartFile(
                "file",
                "制度.md",
                "text/markdown",
                "# 就诊制度".getBytes(StandardCharsets.UTF_8)
        );
    }
}
