package com.crosschecklab.global.error;

import java.util.List;
import lombok.Getter;
import org.springframework.http.HttpStatus;

// 비즈니스 규칙 위반 예외. status/message 는 항상 ErrorCode 에서 가져온다 (여기서 따로 안 바꿈).
// retryable: 같은 요청을 그대로 재시도해도 되는가 (기본 false)
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final boolean retryable;
    private final List<ErrorResponse.FieldError> fieldErrors;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, false, List.of());
    }

    public BusinessException(ErrorCode errorCode, boolean retryable) {
        this(errorCode, retryable, List.of());
    }

    public BusinessException(ErrorCode errorCode, List<ErrorResponse.FieldError> fieldErrors) {
        this(errorCode, false, fieldErrors);
    }

    private BusinessException(ErrorCode errorCode, boolean retryable, List<ErrorResponse.FieldError> fieldErrors) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public HttpStatus getStatus() {
        return errorCode.getStatus();
    }
}
