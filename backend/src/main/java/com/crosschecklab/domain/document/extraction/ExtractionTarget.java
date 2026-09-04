package com.crosschecklab.domain.document.extraction;

import com.crosschecklab.domain.document.ProductDocument;

// 비동기 추출 작업의 입력. 트랜잭션 밖으로 넘기므로 엔티티가 아니라 값만 들고 나간다.
public record ExtractionTarget(Long documentId, String fileName, String mediaType, String storageKey) {

    public static ExtractionTarget from(ProductDocument document) {
        return new ExtractionTarget(document.getId(), document.getFileName(),
                document.getMediaType(), document.getStorageKey());
    }
}
