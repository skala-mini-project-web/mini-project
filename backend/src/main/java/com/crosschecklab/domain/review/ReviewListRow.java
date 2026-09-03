package com.crosschecklab.domain.review;

// REV-002 목록 한 행. 상품명·담당자명·최고 위험도는 reviews 에 없어 조인으로 끌어온다.
// 네이티브 쿼리 별칭에 맞춘 인터페이스 프로젝션이다.
public interface ReviewListRow {

    Long getReviewId();

    Long getAnalysisId();

    String getProductName();

    String getOwnerName();

    String getStatus();

    // Finding 이 하나도 없는 분석이면 null 이다.
    String getMaxSeverity();
}
