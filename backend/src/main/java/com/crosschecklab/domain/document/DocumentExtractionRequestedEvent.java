package com.crosschecklab.domain.document;

// 추출을 시작해도 좋다는 신호. 최초 업로드(DOC-001)와 재시도(DOC-004)가 같은 이벤트를 쓴다.
// 요청 트랜잭션이 커밋된 뒤에 처리해야 한다. 커밋 전에 비동기 스레드가 돌면
// 새 트랜잭션에서 문서를 못 찾거나 바뀌기 전 상태를 읽는다.
public record DocumentExtractionRequestedEvent(Long documentId) {
}
