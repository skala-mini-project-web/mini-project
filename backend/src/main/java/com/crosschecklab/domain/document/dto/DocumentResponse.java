package com.crosschecklab.domain.document.dto;

import com.crosschecklab.domain.document.ProductDocument;
import com.crosschecklab.domain.document.extraction.DocumentExtractionPage;
import com.crosschecklab.global.common.enums.ExtractStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

// 문서 상세. extractedText 는 READY 이전에는 null 이다.
// 이 응답을 만드는 조회는 어떤 상태도 변경하지 않는다 (폴링해도 안전하다).
public record DocumentResponse(
        Long documentId,
        Long productId,
        String fileName,
        String mediaType,
        Long fileSize,
        String checksum,
        ExtractStatus extractStatus,
        String extractedText,
        ExtractionError error,
        boolean confirmed,
        Long confirmedBy,
        OffsetDateTime confirmedAt,
        Long currentRunId,
        String currentTextHash,
        boolean requiresConfirmation,
        List<PageProvenance> pages,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static DocumentResponse from(ProductDocument document) {
        return from(document, List.of());
    }

    public static DocumentResponse from(
            ProductDocument document,
            List<DocumentExtractionPage> extractionPages
    ) {
        Long currentRunId = document.getCurrentExtractionRunId();
        boolean currentConfirmation = document.isConfirmed()
                && (currentRunId == null
                || (Objects.equals(document.getConfirmedExtractionRunId(), currentRunId)
                && Objects.equals(document.getConfirmedTextHash(), document.getExtractedTextHash())));
        return new DocumentResponse(
                document.getId(),
                document.getProductId(),
                document.getFileName(),
                document.getMediaType(),
                document.getFileSize(),
                document.getChecksum(),
                document.getExtractStatus(),
                document.getExtractedText(),
                document.getExtractStatus() == ExtractStatus.FAILED
                        ? new ExtractionError(
                                document.getExtractionErrorCode(),
                                document.getExtractionErrorMessage(),
                                document.isExtractionErrorRetryable())
                        : null,
                currentConfirmation,
                document.getConfirmedById(),
                document.getConfirmedAt(),
                currentRunId,
                document.getExtractedTextHash(),
                document.getExtractStatus() == ExtractStatus.READY && !currentConfirmation,
                extractionPages.stream().map(page -> PageProvenance.from(document.getId(), page)).toList(),
                document.getCreatedAt(),
                document.getUpdatedAt());
    }

    public record ExtractionError(
            String errorCode,
            String message,
            boolean retryable
    ) {
    }

    public record PageProvenance(
            int pageNumber,
            String selectedMethod,
            String textHash,
            String renderArtifactHash,
            String renderArtifactUrl,
            BigDecimal ocrConfidence,
            String ocrConfidenceBand,
            String ocrEngine,
            String ocrModelVersion,
            String ocrLanguage,
            List<String> ocrWarnings
    ) {
        private static PageProvenance from(Long documentId, DocumentExtractionPage page) {
            BigDecimal confidence = page.getOcrConfidence();
            String confidenceBand = confidence == null ? null
                    : confidence.compareTo(BigDecimal.valueOf(90)) >= 0 ? "HIGH"
                    : confidence.compareTo(BigDecimal.valueOf(70)) >= 0 ? "MEDIUM"
                    : "LOW";
            String artifactUrl = page.getOcrRenderArtifactKey() == null ? null
                    : "/api/documents/" + documentId + "/pages/" + page.getPageNumber() + "/render";
            return new PageProvenance(
                    page.getPageNumber(),
                    page.getSelectedMethod().name(),
                    page.getSelectedTextHash(),
                    page.getOcrRenderArtifactHash(),
                    artifactUrl,
                    confidence,
                    confidenceBand,
                    page.getOcrEngine(),
                    page.getOcrModelVersion(),
                    page.getOcrLanguage(),
                    page.getOcrWarnings() == null ? List.of() : page.getOcrWarnings());
        }
    }
}
