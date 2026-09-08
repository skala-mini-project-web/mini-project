-- Deployment MUST stop all pre-fencing application workers before this migration.
-- Rolling deployment with mixed fenced and pre-fencing workers is not guaranteed.

ALTER TABLE product_documents
    ADD COLUMN extraction_token VARCHAR(36),
    ADD COLUMN extraction_lease_until TIMESTAMPTZ,
    ADD CONSTRAINT ck_product_documents_extraction_token
        CHECK (extraction_token IS NULL OR btrim(extraction_token) <> ''),
    ADD CONSTRAINT ck_product_documents_extraction_lease
        CHECK (
            extraction_lease_until IS NULL
            OR (
                extraction_token IS NOT NULL
                AND extract_status IN ('UPLOADED', 'EXTRACTING')
            )
        );

UPDATE product_documents AS document
SET extraction_token = gen_random_uuid()::text,
    extraction_lease_until = clock_timestamp()
WHERE document.extract_status IN ('UPLOADED', 'EXTRACTING')
  AND NOT EXISTS (
      SELECT 1
      FROM document_batch_items AS batch_item
      WHERE batch_item.product_document_id = document.id
  );

CREATE INDEX idx_product_documents_active_extraction_lease
    ON product_documents (extraction_lease_until, id)
    WHERE extract_status IN ('UPLOADED', 'EXTRACTING')
      AND extraction_lease_until IS NOT NULL;

COMMENT ON COLUMN product_documents.extraction_token IS
    'Fence token for a single-document extraction claim; retained after terminal transition.';
COMMENT ON COLUMN product_documents.extraction_lease_until IS
    'Database-clock lease deadline for an active single-document extraction claim.';
