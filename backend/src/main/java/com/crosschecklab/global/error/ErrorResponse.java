package com.crosschecklab.global.error;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.OffsetDateTime;
import java.util.List;

// 요청 단계 실패 공통 응답 스키마.
// record 필드 순서 = JSON 키 순서 (바꾸지 말 것)
public record ErrorResponse(
        int status,
        String errorCode,
        String message,
        List<FieldError> fieldErrors,
        boolean retryable,
        String traceId,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
        OffsetDateTime timestamp
) {

    // 검증 실패한 개별 필드. 검증 오류가 아니면 빈 배열.
    public record FieldError(String field, String message) {
    }

    public static ErrorResponse of(ErrorCode errorCode, String message, boolean retryable,
                                   List<FieldError> fieldErrors, String traceId, OffsetDateTime timestamp) {
        return new ErrorResponse(
                errorCode.getStatus().value(),
                errorCode.name(),
                message,
                fieldErrors == null ? List.of() : List.copyOf(fieldErrors),
                retryable,
                traceId,
                timestamp
        );
    }
}
