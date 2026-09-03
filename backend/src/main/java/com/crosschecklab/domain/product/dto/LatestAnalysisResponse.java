package com.crosschecklab.domain.product.dto;

import com.crosschecklab.domain.analysis.ProductLatestAnalysis;
import com.crosschecklab.global.common.enums.AnalysisStatus;

// 상품의 최신 분석 요약. 분석 이력이 없으면 상위 응답에서 null 로 내려간다.
public record LatestAnalysisResponse(
        Long analysisId,
        AnalysisStatus status
) {

    public static LatestAnalysisResponse from(ProductLatestAnalysis latest) {
        return new LatestAnalysisResponse(latest.analysisId(), latest.status());
    }
}
