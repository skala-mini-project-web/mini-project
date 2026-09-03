package com.crosschecklab.domain.document;

// 업로드 트랜잭션이 커밋된 뒤에 추출을 시작시키기 위한 신호.
// 커밋 전에 비동기 스레드가 돌면 새 트랜잭션에서 문서를 못 찾으므로 이벤트로 미룬다.
public record DocumentUploadedEvent(Long documentId) {
}
