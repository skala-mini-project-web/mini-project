package com.crosschecklab.domain.document.dto;

import com.crosschecklab.domain.document.ProductDocument;
import com.crosschecklab.global.common.enums.ExtractStatus;

// 추출 요청 수락(202) 응답. 업로드와 재시도가 같은 형태를 쓴다.
// 추출이 아직 진행 중이므로 텍스트는 담지 않는다.
// statusUrl 을 폴링해 extractStatus 가 READY/FAILED 가 될 때까지 기다리면 된다.
public record DocumentAcceptedResponse(
        Long documentId,
        Long productId,
        String fileName,
        ExtractStatus extractStatus,
        String statusUrl
) {

    public static DocumentAcceptedResponse from(ProductDocument document) {
        return new DocumentAcceptedResponse(
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
