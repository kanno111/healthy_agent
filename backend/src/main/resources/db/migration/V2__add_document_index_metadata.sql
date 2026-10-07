ALTER TABLE knowledge_document
    ADD COLUMN indexed BOOLEAN NOT NULL DEFAULT FALSE AFTER uploader_id,
    ADD COLUMN chunk_count INT NOT NULL DEFAULT 0 AFTER indexed,
    ADD COLUMN indexed_at TIMESTAMP(6) NULL AFTER chunk_count,
    ADD CONSTRAINT chk_knowledge_document_chunk_count CHECK (chunk_count >= 0);
