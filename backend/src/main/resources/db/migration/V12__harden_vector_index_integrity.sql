ALTER TABLE evidence_document_chunks
    ADD CONSTRAINT ck_evidence_document_chunks_embedding_non_zero
        CHECK (vector_norm(embedding) > 0);

COMMENT ON CONSTRAINT ck_evidence_document_chunks_embedding_non_zero
    ON evidence_document_chunks IS
    'Cosine retrieval requires embeddings with non-zero Euclidean norm.';
