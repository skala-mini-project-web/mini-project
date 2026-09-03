package com.crosschecklab.domain.dashboard;

// DASH-002 집계 한 행. 네이티브 쿼리 별칭에 맞춘 인터페이스 프로젝션이다.
// DASH-001 과 같은 이유로 카운트만 담고 엔티티를 올리지 않는다.
public interface ComplianceSummaryRow {

    Long getPendingReviews();

    Long getHighFindings();

    Long getActiveRiskPatterns();

    Long getDecidedInRange();
}
