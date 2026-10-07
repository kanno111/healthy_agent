UPDATE knowledge_document
SET indexed = FALSE,
    chunk_count = 0,
    indexed_at = NULL
WHERE indexed = TRUE;
