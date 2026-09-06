package com.crosschecklab.domain.document.extraction;

// 추출 실패. 사용자에게 직접 노출되지 않고 문서 상태를 FAILED 로 만드는 신호로만 쓰인다.
public class TextExtractionException extends RuntimeException {

    private final boolean retryable;

    public TextExtractionException(String message) {
        this(message, false);
    }

    public TextExtractionException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public TextExtractionException(String message, Throwable cause) {
        super(message, cause);
        this.retryable = false;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
