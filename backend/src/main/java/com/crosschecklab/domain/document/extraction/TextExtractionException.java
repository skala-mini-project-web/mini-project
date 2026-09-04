package com.crosschecklab.domain.document.extraction;

// 추출 실패. 사용자에게 직접 노출되지 않고 문서 상태를 FAILED 로 만드는 신호로만 쓰인다.
public class TextExtractionException extends RuntimeException {

    public TextExtractionException(String message) {
        super(message);
    }

    public TextExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
