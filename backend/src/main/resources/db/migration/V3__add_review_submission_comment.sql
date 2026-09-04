ALTER TABLE reviews
    ADD COLUMN submission_comment VARCHAR(500);

COMMENT ON COLUMN reviews.submission_comment IS '상품 담당자가 검토 요청 시 남긴 선택 의견. 검토자의 결정 사유(comment)와 별도로 보존한다.';
