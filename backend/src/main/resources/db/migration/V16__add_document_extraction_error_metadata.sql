ALTER TABLE product_documents
    ADD COLUMN extraction_error_code VARCHAR(60),
    ADD COLUMN extraction_error_message VARCHAR(500),
    ADD COLUMN extraction_error_retryable BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE product_documents
SET extraction_error_code = 'DOCUMENT_EXTRACTION_FAILED',
    extraction_error_message = '문서에서 텍스트를 추출하지 못했습니다.'
WHERE extract_status = 'FAILED';

ALTER TABLE product_documents
    ADD CONSTRAINT ck_product_documents_extraction_error_metadata
        CHECK (
            (extract_status = 'FAILED'
                AND extraction_error_code IS NOT NULL
                AND btrim(extraction_error_code) <> ''
                AND extraction_error_message IS NOT NULL
                AND btrim(extraction_error_message) <> '')
            OR
            (extract_status <> 'FAILED'
                AND extraction_error_code IS NULL
                AND extraction_error_message IS NULL
                AND extraction_error_retryable = FALSE)
        );

COMMENT ON COLUMN product_documents.extraction_error_code IS
    'Stable public error code populated only while extraction is FAILED.';
COMMENT ON COLUMN product_documents.extraction_error_message IS
    'Sanitized public extraction failure message; never stores exception details.';
COMMENT ON COLUMN product_documents.extraction_error_retryable IS
    'Whether the persisted extraction failure is eligible for an owner-initiated retry.';
