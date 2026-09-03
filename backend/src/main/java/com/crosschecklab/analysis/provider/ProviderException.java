package com.crosschecklab.analysis.provider;

import com.crosschecklab.global.error.ErrorCode;
import lombok.Getter;

// Provider 실행 실패. 202 로 이미 수락된 작업의 실패이므로 HTTP 응답이 아니라 Analysis 행에 기록된다.
@Getter
public class ProviderException extends RuntimeException {

    private final ErrorCode errorCode;
    private final boolean retryable;

    public ProviderException(ErrorCode errorCode, boolean retryable, String message) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }
}
