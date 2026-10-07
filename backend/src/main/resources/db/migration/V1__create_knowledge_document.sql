CREATE TABLE knowledge_document (
    id            CHAR(36)      NOT NULL,
    original_name VARCHAR(255)  NOT NULL,
    object_key    VARCHAR(512)  NOT NULL,
    content_type  VARCHAR(128)  NOT NULL,
    size_bytes    BIGINT        NOT NULL,
    sha256        CHAR(64)      NOT NULL,
    uploader_id   BIGINT        NOT NULL,
    created_at    TIMESTAMP(6)  NOT NULL,
    updated_at    TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_document_object_key (object_key),
    KEY idx_knowledge_document_created_at (created_at),
    KEY idx_knowledge_document_sha256 (sha256),
    CONSTRAINT chk_knowledge_document_size CHECK (size_bytes >= 0)
) ENGINE=InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
