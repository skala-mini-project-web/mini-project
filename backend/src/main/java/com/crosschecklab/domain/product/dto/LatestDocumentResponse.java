package com.crosschecklab.domain.product.dto;

import com.crosschecklab.domain.document.ProductDocument;
import com.crosschecklab.global.common.enums.ExtractStatus;

// 상품의 최신 문서 요약. 문서가 아직 없으면 상위 응답에서 null 로 내려간다.
public record LatestDocumentResponse(
        Long documentId,
        ExtractStatus extractStatus,
        boolean confirmed
) {

    public static LatestDocumentResponse from(ProductDocument document) {
        return new LatestDocumentResponse(document.getId(), document.getExtractStatus(), document.isConfirmed());
    }
}
