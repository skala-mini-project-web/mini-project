package com.crosschecklab.global.common.enums;

/**
 * 문서 추출 상태. UPLOADED → EXTRACTING → READY 또는 FAILED.
 * GET Polling은 이 상태를 변경하지 않는다.
 */
public enum ExtractStatus {
    UPLOADED,
    EXTRACTING,
    READY,
    FAILED
}
