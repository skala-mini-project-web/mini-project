package com.crosschecklab.domain.evidence.dto;

import com.crosschecklab.domain.evidence.EvidenceDocument;
import com.crosschecklab.global.common.enums.EvidenceSourceType;

// content 를 함께 내린다. 분석 결과의 근거 인용 화면이 원문을 그대로 보여주고,
// 데모 근거 문서는 문단 단위로 짧아 목록 응답에 담아도 부담이 없다.
public record EvidenceDocumentResponse(
        Long evidenceDocumentId,
        EvidenceSourceType sourceType,
        String title,
        String version,
        String content,
        boolean active
) {

    public static EvidenceDocumentResponse from(EvidenceDocument document) {
        return new EvidenceDocumentResponse(
                document.getId(),
                document.getSourceType(),
                document.getTitle(),
                document.getVersion(),
                document.getContent(),
                document.isActive()
        );
    }
}
