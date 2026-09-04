package com.crosschecklab.domain.document.extraction;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

// 문서별 추출 시도 횟수. "1차 실패 후 재시도 성공" 데모를 재현하는 데만 쓴다.
// 데모 연출용 상태라서 DB 컬럼으로 만들지 않는다. 재기동하면 초기화되는 것이 의도된 동작이다.
@Component
public class MockExtractionContext {

    private final Map<Long, AtomicInteger> attemptsByDocumentId = new ConcurrentHashMap<>();

    // 이번 시도가 몇 번째인지 돌려준다 (첫 호출이 1).
    public int nextAttempt(Long documentId) {
        return attemptsByDocumentId.computeIfAbsent(documentId, key -> new AtomicInteger())
                .incrementAndGet();
    }

    public void reset(Long documentId) {
        attemptsByDocumentId.remove(documentId);
    }
}
