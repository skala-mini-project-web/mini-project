package com.crosschecklab.domain.document;

import com.crosschecklab.domain.document.extraction.ExtractionTarget;
import com.crosschecklab.domain.document.extraction.TextExtractionException;
import com.crosschecklab.domain.document.extraction.TextExtractionService;
import com.crosschecklab.global.config.AsyncConfig;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// DOC-002. UPLOADED 상태의 문서를 백그라운드에서 EXTRACTING → READY/FAILED 로 옮긴다.
// 업로드 응답(202)은 이 작업을 기다리지 않으며, 클라이언트는 statusUrl 을 폴링해 결과를 확인한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentExtractionRunner {

    private static final String EXTRACTION_FAILED_CODE = "DOCUMENT_EXTRACTION_FAILED";
    private static final String EXTRACTION_FAILED_MESSAGE = "문서에서 텍스트를 추출하지 못했습니다.";
    private static final String TEMPORARY_FAILURE_CODE = "DOCUMENT_EXTRACTION_TEMPORARY_FAILURE";
    private static final String TEMPORARY_FAILURE_MESSAGE =
            "문서 추출 중 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.";

    private final DocumentExtractionTransitions transitions;
    private final TextExtractionService textExtractionService;

    // 요청 트랜잭션이 커밋된 뒤에 실행한다. 커밋 전이면 새 트랜잭션에서 문서를 찾지 못한다.
    @Async(AsyncConfig.ANALYSIS_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onExtractionRequested(DocumentExtractionRequestedEvent event) {
        run(event.documentId());
    }

    // 업로드와 재시도가 같은 경로를 타도록 공개 메서드로 둔다.
    public void run(Long documentId) {
        Optional<ExtractionTarget> target = transitions.beginExtraction(documentId);
        if (target.isEmpty()) {
            log.warn("추출을 시작할 문서가 없습니다. documentId={}", documentId);
            return;
        }

        try {
            String extractedText = textExtractionService.extract(target.get());
            transitions.completeExtraction(documentId, extractedText);
            log.info("문서 {} 추출 완료 ({}자)", documentId, extractedText.length());
        } catch (TextExtractionException e) {
            log.warn("문서 {} 추출 실패", documentId, e);
            transitions.failExtraction(
                    documentId,
                    EXTRACTION_FAILED_CODE,
                    EXTRACTION_FAILED_MESSAGE,
                    e.isRetryable());
        } catch (RuntimeException e) {
            log.error("문서 {} 추출 중 예기치 않은 오류", documentId, e);
            transitions.failExtraction(
                    documentId,
                    TEMPORARY_FAILURE_CODE,
                    TEMPORARY_FAILURE_MESSAGE,
                    true);
        }
    }
}
