package com.crosschecklab.domain.document.dto;

import com.crosschecklab.domain.document.ProductDocument;
import com.crosschecklab.global.common.enums.ExtractStatus;
import java.time.OffsetDateTime;

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
        boolean confirmed,
        Long confirmedBy,
        OffsetDateTime confirmedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static DocumentResponse from(ProductDocument document) {
        return new DocumentResponse(
                document.getId(),
                document.getProductId(),
                document.getFileName(),
                document.getMediaType(),
                document.getFileSize(),
                document.getChecksum(),
                document.getExtractStatus(),
                document.getExtractedText(),
                document.isConfirmed(),
                document.getConfirmedById(),
                document.getConfirmedAt(),
                document.getCreatedAt(),
                document.getUpdatedAt());
    }
}
