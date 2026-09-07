package com.crosschecklab.domain.document;

import com.crosschecklab.domain.document.batch.DocumentBatchClaimRepository;
import com.crosschecklab.domain.document.extraction.ExtractionTarget;
import java.time.OffsetDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 추출 상태 전이만 담당한다.
// 전이마다 독립 트랜잭션(REQUIRES_NEW)으로 커밋해야 폴링 중인 클라이언트가 중간 상태를 볼 수 있고,
// 추출이 실패해도 앞선 전이가 함께 롤백되지 않는다.
// 별도 빈으로 분리한 이유는 같은 클래스 안에서 호출하면 프록시를 타지 않아 전파 설정이 무시되기 때문이다.
@Component
@RequiredArgsConstructor
public class DocumentExtractionTransitions {

    private static final String FAILURE_LOCK_TIMEOUT = "2s";
    private static final String FAILURE_STATEMENT_TIMEOUT = "5s";

    private final ProductDocumentRepository productDocumentRepository;
    private final DocumentBatchClaimRepository batchClaimRepository;

    // 문서가 이미 지워졌을 수 있으므로 Optional 로 돌려준다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ExtractionTarget> beginExtraction(Long documentId) {
        return productDocumentRepository.findById(documentId)
                .map(document -> {
                    document.markExtracting();
                    return ExtractionTarget.from(document);
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeExtraction(Long documentId, String extractedText) {
        productDocumentRepository.findById(documentId)
                .ifPresent(document -> document.markReady(extractedText));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failExtraction(Long documentId, String errorCode, String publicMessage, boolean retryable) {
        productDocumentRepository.findById(documentId)
                .ifPresent(document -> document.markFailed(errorCode, publicMessage, retryable));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ExtractionTarget> beginBatchExtraction(
            Long itemId,
            String workerOwner,
            long leaseFence,
            OffsetDateTime startedAt
    ) {
        if (batchClaimRepository.lockClaimBatch(itemId, workerOwner, leaseFence).isEmpty()) {
            return Optional.empty();
        }
        // Empty is deliberately not classified here: cancellation, a stale fence, and an internal
        // dependent-row inconsistency are indistinguishable to the native statement. The worker
        // resolves it by attempting failBatchExtraction with the same owner and fence.
        return batchClaimRepository.beginClaimedExtraction(itemId, workerOwner, leaseFence, startedAt)
                .map(target -> new ExtractionTarget(
                        target.getDocumentId(),
                        target.getFileName(),
                        target.getMediaType(),
                        target.getStorageKey()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean completeBatchExtraction(
            Long itemId,
            String workerOwner,
            long leaseFence,
            OffsetDateTime finishedAt,
            String extractedText
    ) {
        if (batchClaimRepository.lockClaimBatch(itemId, workerOwner, leaseFence).isEmpty()) {
            return false;
        }
        // The worker must pass a zero-row result through failBatchExtraction. That second fenced
        // transition closes a still-current claim while remaining a no-op for a stale claim.
        int affectedRows = batchClaimRepository.completeClaim(
                itemId, workerOwner, leaseFence, finishedAt, extractedText);
        return affectedRows == 1;
    }

    /**
     * Runs on the caller thread in its own transaction. In particular, timeout fencing must not be
     * queued behind the lifecycle executor whose thread may still be stuck.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failBatchExtraction(
            Long itemId,
            String workerOwner,
            long leaseFence,
            OffsetDateTime finishedAt,
            OffsetDateTime nextDueAt,
            boolean retryable,
            String errorCode,
            String errorMessage,
            String quarantineReason,
            String diagnosticReference
    ) {
        batchClaimRepository.setLocalLockTimeout(FAILURE_LOCK_TIMEOUT);
        batchClaimRepository.setLocalStatementTimeout(FAILURE_STATEMENT_TIMEOUT);
        if (batchClaimRepository.lockClaimBatch(itemId, workerOwner, leaseFence).isEmpty()) {
            return false;
        }
        int affectedRows = batchClaimRepository.failClaim(
                itemId,
                workerOwner,
                leaseFence,
                finishedAt,
                nextDueAt,
                retryable,
                errorCode,
                errorMessage,
                quarantineReason,
                diagnosticReference);
        return affectedRows == 1;
    }
}
