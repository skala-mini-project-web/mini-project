package com.crosschecklab.global.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

// API 에러 코드 레지스트리.
// errorCode 문자열은 enum 이름 그대로, HTTP status 는 여기서만 정의
@Getter
public enum ErrorCode {

    // --- 400 Bad Request ---
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    INVALID_SELECTION_COUNT(HttpStatus.BAD_REQUEST, "선택 개수가 올바르지 않습니다. (1~3개)"),
    INVALID_EVIDENCE_DOCUMENT(HttpStatus.BAD_REQUEST, "사용할 수 없는 근거 문서가 포함되어 있습니다."),
    INVALID_FINDING_SELECTION(HttpStatus.BAD_REQUEST, "승인 시 반영할 Finding 을 1건 이상 선택해야 합니다."),
    COMMENT_REQUIRED(HttpStatus.BAD_REQUEST, "반려 시 사유(comment)는 필수입니다."),
    INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "조회 기간이 올바르지 않습니다."),

    // --- 403 Forbidden ---
    FORBIDDEN(HttpStatus.FORBIDDEN, "해당 작업을 수행할 권한이 없습니다."),

    // --- 404 / 405 / 415 ---
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type 입니다."),

    // --- 409 Conflict ---
    DOCUMENT_NOT_CONFIRMED(HttpStatus.CONFLICT, "추출 텍스트 확인 후 분석을 요청하세요."),
    ANALYSIS_ALREADY_RUNNING(HttpStatus.CONFLICT, "이미 진행 중인 분석입니다."),
    ANALYSIS_NOT_RETRYABLE(HttpStatus.CONFLICT, "재시도할 수 없는 분석입니다."),
    ANALYSIS_NOT_COMPLETED(HttpStatus.CONFLICT, "분석이 완료된 후에 조회할 수 있습니다."),
    REVIEW_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 검토 요청이 생성된 분석입니다."),
    REVIEW_ALREADY_DECIDED(HttpStatus.CONFLICT, "이미 결정이 완료된 검토입니다."),
    RISK_PATTERN_NOT_ACTIVE(HttpStatus.CONFLICT, "활성 상태(ACTIVE)인 리스크 패턴이 아닙니다."),
    ACTION_ALREADY_FINALIZED(HttpStatus.CONFLICT, "승인 완료된 액션은 수정할 수 없습니다."),

    // --- 503 Service Unavailable ---
    // 외부 ai-service 응답이 계약을 어긴 경우 (스키마 불일치, HIGH Finding 근거 0건 등)
    PROVIDER_RESPONSE_INVALID(HttpStatus.SERVICE_UNAVAILABLE, "분석 결과가 형식 요건을 충족하지 않습니다."),

    // --- 500 Internal Server Error ---
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }
}
