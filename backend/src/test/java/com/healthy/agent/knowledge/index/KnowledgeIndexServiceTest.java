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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeIndexServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-07T02:00:00Z");

    private KnowledgeDocumentRepository documentRepository;
    private DocumentMetadataWriter metadataWriter;
    private KnowledgeDocumentStorage storage;
    private KnowledgeDocumentParser parser;
    private EmbeddingClient embeddingClient;
    private KnowledgeChunkRepository chunkRepository;
    private KnowledgeIndexService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(KnowledgeDocumentRepository.class);
        metadataWriter = mock(DocumentMetadataWriter.class);
        storage = mock(KnowledgeDocumentStorage.class);
        parser = mock(KnowledgeDocumentParser.class);
        embeddingClient = mock(EmbeddingClient.class);
        chunkRepository = mock(KnowledgeChunkRepository.class);
        service = new KnowledgeIndexService(
                documentRepository,
                metadataWriter,
                storage,
                parser,
                new DocumentChunker(100, 10),
                embeddingClient,
                chunkRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void extractsChunksIndexesAndMarksDocumentIndexed() throws Exception {
        KnowledgeDocumentMetadata document = document(false, 0, null);
        ByteArrayInputStream input = new ByteArrayInputStream("source".getBytes(StandardCharsets.UTF_8));
        when(documentRepository.findById(document.id())).thenReturn(Optional.of(document));
        when(storage.open(document.objectKey())).thenReturn(input);
        when(parser.extractText(document, input)).thenReturn("患者预约后请按时到院。".repeat(30));
        when(embeddingClient.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return embeddings(texts.size());
        });

        KnowledgeIndexResult result = service.build(document.id(), false);

        assertThat(result.indexed()).isTrue();
        assertThat(result.chunkCount()).isGreaterThan(1);
        assertThat(result.indexedAt()).isEqualTo(NOW);
        assertThat(result.skipped()).isFalse();
        verify(metadataWriter).markNotIndexed(document.id(), NOW);
        verify(chunkRepository).deleteByDocumentId(document.id());
        verify(chunkRepository).replaceDocumentChunks(
                org.mockito.ArgumentMatchers.eq(document.id()),
                org.mockito.ArgumentMatchers.argThat(chunks -> chunks.stream().allMatch(chunk ->
                        chunk.embeddingModel().equals("BAAI/bge-m3")
                                && chunk.embedding().size() == 1024
                ))
        );
        verify(embeddingClient).embed(anyList());
        verify(metadataWriter).markIndexed(document.id(), result.chunkCount(), NOW);
    }

    @Test
    void skipsAlreadyIndexedDocumentUnlessForced() {
        KnowledgeDocumentMetadata document = document(true, 4, NOW);
        when(documentRepository.findById(document.id())).thenReturn(Optional.of(document));

        KnowledgeIndexResult result = service.build(document.id(), false);

        assertThat(result.chunkCount()).isEqualTo(4);
        assertThat(result.skipped()).isTrue();
        verifyNoInteractions(metadataWriter, storage, parser, embeddingClient, chunkRepository);
    }

    @Test
    void doesNotMarkDocumentIndexedWhenElasticsearchFails() throws Exception {
        KnowledgeDocumentMetadata document = document(false, 0, null);
        ByteArrayInputStream input = new ByteArrayInputStream("source".getBytes(StandardCharsets.UTF_8));
        when(documentRepository.findById(document.id())).thenReturn(Optional.of(document));
        when(storage.open(document.objectKey())).thenReturn(input);
        when(parser.extractText(document, input)).thenReturn("有效的知识文本。".repeat(20));
        when(embeddingClient.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return embeddings(texts.size());
        });
        doThrow(new AgentException(AgentErrorCode.INDEX_UNAVAILABLE))
                .when(chunkRepository).replaceDocumentChunks(org.mockito.ArgumentMatchers.eq(document.id()), anyList());

        assertThatThrownBy(() -> service.build(document.id(), false))
                .isInstanceOfSatisfying(AgentException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(AgentErrorCode.INDEX_UNAVAILABLE));

        verify(metadataWriter).markNotIndexed(document.id(), NOW);
        verify(metadataWriter, never()).markIndexed(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void doesNotWriteElasticsearchWhenEmbeddingFails() throws Exception {
        KnowledgeDocumentMetadata document = document(false, 0, null);
        ByteArrayInputStream input = new ByteArrayInputStream("source".getBytes(StandardCharsets.UTF_8));
        when(documentRepository.findById(document.id())).thenReturn(Optional.of(document));
        when(storage.open(document.objectKey())).thenReturn(input);
        when(parser.extractText(document, input)).thenReturn("有效的知识文本。".repeat(20));
        doThrow(new AgentException(AgentErrorCode.EMBEDDING_UNAVAILABLE))
                .when(embeddingClient).embed(anyList());

        assertThatThrownBy(() -> service.build(document.id(), false))
                .isInstanceOfSatisfying(AgentException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(AgentErrorCode.EMBEDDING_UNAVAILABLE));

        verify(metadataWriter).markNotIndexed(document.id(), NOW);
        verify(chunkRepository, never()).replaceDocumentChunks(
                org.mockito.ArgumentMatchers.anyString(), anyList());
        verify(metadataWriter, never()).markIndexed(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    private EmbeddingBatch embeddings(int count) {
        List<Float> vector = java.util.Collections.nCopies(1024, 0.01f);
        return new EmbeddingBatch(
                "BAAI/bge-m3",
                java.util.stream.IntStream.range(0, count).mapToObj(index -> vector).toList()
        );
    }

    private KnowledgeDocumentMetadata document(boolean indexed, int chunkCount, Instant indexedAt) {
        return new KnowledgeDocumentMetadata(
                "document-id", "制度.md", "documents/document-id/制度.md",
                "text/markdown; charset=utf-8", 100, "a".repeat(64), 12,
                indexed, chunkCount, indexedAt, NOW, NOW
        );
    }
}
