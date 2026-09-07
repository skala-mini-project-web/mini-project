package com.crosschecklab.domain.document.extraction;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Private contract for OCR of exactly one already-rendered PDF page image. */
public interface OcrClient {

    String LANGUAGE = "kor+eng";

    OcrResult recognize(OcrRequest request);

    record OcrRequest(
            byte[] pageImage,
            String mediaType,
            int pageNumber,
            String sourceHash,
            String artifactKey,
            String artifactHash,
            String idempotencyKey) {

        public OcrRequest {
            Objects.requireNonNull(pageImage, "pageImage must not be null");
            pageImage = pageImage.clone();
            if (pageImage.length == 0) {
                throw new IllegalArgumentException("pageImage must not be empty");
            }
            requireNonBlank(mediaType, "mediaType");
            if (pageNumber < 1) {
                throw new IllegalArgumentException("pageNumber must be positive");
            }
            requireSha256(sourceHash, "sourceHash");
            requireNonBlank(artifactKey, "artifactKey");
            requireSha256(artifactHash, "artifactHash");
            requireNonBlank(idempotencyKey, "idempotencyKey");
        }

        @Override
        public byte[] pageImage() {
            return pageImage.clone();
        }
    }

    record OcrResult(
            int pageNumber,
            String sourceHash,
            String artifactKey,
            String artifactHash,
            String idempotencyKey,
            int width,
            int height,
            String text,
            String rawTsv,
            BigDecimal confidence,
            EngineMetadata engine) {
    }

    record EngineMetadata(
            String name,
            String version,
            String tessdataVersion,
            String language,
            List<String> languages) {

        public EngineMetadata {
            languages = languages == null ? null : List.copyOf(languages);
        }
    }

    enum FailureKind {
        TEMPORARY,
        NO_TEXT,
        INVALID_RESPONSE,
        REJECTED
    }

    final class OcrException extends RuntimeException {

        private final FailureKind kind;

        public OcrException(FailureKind kind, String message) {
            super(message);
            this.kind = Objects.requireNonNull(kind, "kind must not be null");
        }

        public OcrException(FailureKind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = Objects.requireNonNull(kind, "kind must not be null");
        }

        public FailureKind kind() {
            return kind;
        }

        public boolean retryable() {
            return kind == FailureKind.TEMPORARY;
        }
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private static void requireSha256(String value, String fieldName) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(fieldName + " must be a lowercase SHA-256 digest");
        }
    }
}
