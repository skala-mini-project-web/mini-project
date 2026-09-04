package com.crosschecklab.global.common.enums;

/**
 * 검토 상태. 별도 decision 필드 없이 status 하나로 결정을 관리한다(확정).
 */
public enum ReviewStatus {
    PENDING,
    APPROVED,
    REJECTED
}
