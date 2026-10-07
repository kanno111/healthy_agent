package com.healthy.agent.knowledge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class KnowledgeDocumentRepository {
    private final JdbcTemplate jdbcTemplate;

    public KnowledgeDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(KnowledgeDocumentMetadata document) {
        jdbcTemplate.update("""
                        INSERT INTO knowledge_document (
                            id, original_name, object_key, content_type, size_bytes,
                            sha256, uploader_id, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                document.id(),
                document.originalName(),
                document.objectKey(),
                document.contentType(),
                document.sizeBytes(),
                document.sha256(),
                document.uploaderId(),
                Timestamp.from(document.createdAt()),
                Timestamp.from(document.updatedAt())
        );
    }

    public List<KnowledgeDocumentMetadata> findAllNewestFirst() {
        return jdbcTemplate.query("""
                        SELECT id, original_name, object_key, content_type, size_bytes,
                               sha256, uploader_id, indexed, chunk_count, indexed_at,
                               created_at, updated_at
                        FROM knowledge_document
                        ORDER BY created_at DESC, id DESC
                        """,
                (resultSet, rowNumber) -> mapDocument(resultSet));
    }

    public Optional<KnowledgeDocumentMetadata> findById(String id) {
        return jdbcTemplate.query("""
                        SELECT id, original_name, object_key, content_type, size_bytes,
                               sha256, uploader_id, indexed, chunk_count, indexed_at,
                               created_at, updated_at
                        FROM knowledge_document
                        WHERE id = ?
                        """,
                (resultSet, rowNumber) -> mapDocument(resultSet),
                id
        ).stream().findFirst();
    }

    public int deleteById(String id) {
        return jdbcTemplate.update("DELETE FROM knowledge_document WHERE id = ?", id);
    }

    public int markNotIndexed(String id, Instant updatedAt) {
        return jdbcTemplate.update("""
                        UPDATE knowledge_document
                        SET indexed = FALSE, chunk_count = 0, indexed_at = NULL, updated_at = ?
                        WHERE id = ?
                        """,
                Timestamp.from(updatedAt), id
        );
    }

    public int markIndexed(String id, int chunkCount, Instant indexedAt) {
        return jdbcTemplate.update("""
                        UPDATE knowledge_document
                        SET indexed = TRUE, chunk_count = ?, indexed_at = ?, updated_at = ?
                        WHERE id = ?
                        """,
                chunkCount, Timestamp.from(indexedAt), Timestamp.from(indexedAt), id
        );
    }

    private KnowledgeDocumentMetadata mapDocument(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new KnowledgeDocumentMetadata(
                resultSet.getString("id"),
                resultSet.getString("original_name"),
                resultSet.getString("object_key"),
                resultSet.getString("content_type"),
                resultSet.getLong("size_bytes"),
                resultSet.getString("sha256"),
                resultSet.getLong("uploader_id"),
                resultSet.getBoolean("indexed"),
                resultSet.getInt("chunk_count"),
                resultSet.getTimestamp("indexed_at") == null
                        ? null : resultSet.getTimestamp("indexed_at").toInstant(),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }
}
