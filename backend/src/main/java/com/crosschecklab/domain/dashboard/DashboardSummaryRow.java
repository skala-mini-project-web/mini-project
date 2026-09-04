package com.crosschecklab.domain.dashboard;

// DASH-001 집계 한 행. 네이티브 쿼리 별칭에 맞춘 인터페이스 프로젝션이다.
// 카운트만 필요해 트랙 B 의 Analysis / Review 엔티티를 올리지 않는다.
public interface DashboardSummaryRow {

    Long getMyProducts();

    Long getAnalyzing();

    Long getPendingReview();

    Long getApproved();
}
