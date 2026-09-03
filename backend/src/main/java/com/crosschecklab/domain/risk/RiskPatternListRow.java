package com.crosschecklab.domain.risk;

// RISK-001 목록 한 행. 네이티브 쿼리 별칭에 맞춘 인터페이스 프로젝션이다.
// 영향 Persona·근거는 여기에 싣지 않고 findingId 로 역추적한다 (ERD 주석과 동일한 원칙).
public interface RiskPatternListRow {

    Long getRiskPatternId();

    Long getFindingId();

    // 승격시킨 Review 가 지워지면 null 이 될 수 있다 (ON DELETE SET NULL).
    Long getReviewId();

    String getName();

    String getSeverity();

    String getStatus();
}
