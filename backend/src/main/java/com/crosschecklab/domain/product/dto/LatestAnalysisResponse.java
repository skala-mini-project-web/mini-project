package com.crosschecklab.domain.product.dto;

import com.crosschecklab.global.common.enums.AnalysisStatus;

// 상품의 최신 분석 요약.
// 트랙 B 의 analyses 테이블·엔티티가 아직 없어 현재는 항상 null 로 내려간다.
// FE 가 나중에 응답 구조를 바꾸지 않도록 계약만 먼저 고정해 둔다. B 머지 후 값을 채운다.
public record LatestAnalysisResponse(
        Long analysisId,
        AnalysisStatus status
) {
}
