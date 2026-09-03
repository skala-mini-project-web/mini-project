package com.crosschecklab.domain.analysis;

import com.crosschecklab.global.common.enums.AnalysisStatus;

// 상품 응답에 붙일 최신 분석 요약. Analysis 는 product 를 직접 들고 있지 않고
// product_documents 를 거쳐야 상품이 나오므로, 조회 결과에 productId 를 함께 담아 돌려준다.
public record ProductLatestAnalysis(
        Long productId,
        Long analysisId,
        AnalysisStatus status
) {
}
