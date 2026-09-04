package com.crosschecklab.global.common.enums;

/**
 * 분석 상태. RETRYING은 별도 상태로 저장하지 않는다(확정).
 * Review가 생성되면 COMPLETED → IN_REVIEW로 전이한다.
 */
public enum AnalysisStatus {
    CREATED,
    RUNNING,
    COMPLETED,
    IN_REVIEW,
    FAILED
}
