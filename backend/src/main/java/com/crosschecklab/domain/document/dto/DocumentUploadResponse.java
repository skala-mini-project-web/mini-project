package com.crosschecklab.domain.document.dto;

import com.crosschecklab.domain.document.ProductDocument;
import com.crosschecklab.global.common.enums.ExtractStatus;

// 업로드 수락(202) 응답. 추출은 아직 진행 중이므로 텍스트를 담지 않는다.
// statusUrl 을 폴링해 extractStatus 가 READY/FAILED 가 될 때까지 기다리면 된다.
public record DocumentUploadResponse(
        Long documentId,
        Long productId,
        String fileName,
        ExtractStatus extractStatus,
        String statusUrl
) {

    public static DocumentUploadResponse from(ProductDocument document) {
        return new DocumentUploadResponse(
                document.getId(),
                document.getProductId(),
                document.getFileName(),
                document.getExtractStatus(),
                statusUrlOf(document.getId()));
    }

    public static String statusUrlOf(Long documentId) {
        return "/api/documents/" + documentId;
    }
}
