package com.crosschecklab.domain.document;

// 추출을 시작해도 좋다는 신호. 최초 업로드(DOC-001)와 재시도(DOC-004)가 같은 이벤트를 쓴다.
// 요청 트랜잭션이 커밋된 뒤에 처리해야 한다. 커밋 전에 비동기 스레드가 돌면
// 새 트랜잭션에서 문서를 못 찾거나 바뀌기 전 상태를 읽는다.
//
// 한계: 이 신호는 프로세스 메모리에만 있다. 커밋 직후 리스너가 실행되기 전에 프로세스가 죽으면
// 문서가 EXTRACTING 에 멈추고, 재시도는 FAILED 만 받으므로 스스로 복구되지 않는다.
// 데모(단일 인스턴스, Mock 추출) 범위에서 감수한 제약이다. 실제 운영으로 가려면
// 멈춘 EXTRACTING 을 회수하는 장치가 필요하며, 분석 작업도 같은 문제를 공유하므로 함께 정해야 한다.
public record DocumentExtractionRequestedEvent(Long documentId) {
}
