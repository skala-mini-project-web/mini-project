package com.crosschecklab.analysis.application;

// 분석 실행 요청. CREATED 행이 커밋된 뒤에만 백그라운드 작업이 시작되도록 트랜잭션 이벤트로 전달한다.
public record AnalysisRequestedEvent(Long analysisId, String scenarioCode) {
}
