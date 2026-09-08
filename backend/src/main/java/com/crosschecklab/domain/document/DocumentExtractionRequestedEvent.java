package com.crosschecklab.domain.document;

// 추출을 시작해도 좋다는 fenced 신호. 요청 트랜잭션이 커밋된 뒤에 처리해야 한다.
// expectedRequestToken 이 현재 문서의 token/lease 와 일치하는 실행만 작업을 인수할 수 있다.
public record DocumentExtractionRequestedEvent(Long documentId, String expectedRequestToken) {
}
