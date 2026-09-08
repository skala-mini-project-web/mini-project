package com.crosschecklab.domain.document;

import com.crosschecklab.domain.document.batch.DocumentBatchClaimRepository;
import com.crosschecklab.domain.document.extraction.DocumentExtractionPage;
import com.crosschecklab.domain.document.extraction.DocumentExtractionResult;
import com.crosschecklab.domain.document.extraction.DocumentExtractionRun;
import com.crosschecklab.domain.document.extraction.ExtractionTarget;
import com.crosschecklab.domain.document.extraction.PageExtractionMethod;
import com.crosschecklab.domain.document.extraction.PageExtractionResult;
import com.crosschecklab.domain.document.extraction.TextExtractionException;
import com.crosschecklab.global.common.enums.ExtractStatus;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
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
    private static final String EXTRACTION_CONFIG_VERSION = "pdfbox-ocr-v1";
    private static final Duration EXTRACTION_LEASE = Duration.ofMinutes(10);
    private static final Duration EXTRACTION_LEASE_CLEANUP_MARGIN = Duration.ofSeconds(30);
    private static final String TEMPORARY_FAILURE_CODE =
            "DOCUMENT_EXTRACTION_TEMPORARY_FAILURE";
    private static final String TEMPORARY_FAILURE_MESSAGE =
            "문서 추출 중 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.";

    private final ProductDocumentRepository productDocumentRepository;
    private final DocumentBatchClaimRepository batchClaimRepository;
    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ExtractionClaim> claimExtraction(
            Long documentId,
            String expectedRequestToken,
            Duration processingBudget
    ) {
        requirePositiveBudget(processingBudget);
        long claimStarted = System.nanoTime();
        setLocalTimeouts();
        Optional<ProductDocument> locked = productDocumentRepository.findByIdForUpdate(documentId);
        if (locked.isEmpty() || productDocumentRepository.hasBatchMembership(documentId)) {
            return Optional.empty();
        }
        ProductDocument document = locked.get();
        OffsetDateTime now = databaseNow();
        Duration remainingBudget = processingBudget.minus(Duration.ofNanos(
                Math.max(0L, System.nanoTime() - claimStarted)));
        if (!document.ownsExtraction(expectedRequestToken, now)
                || remainingBudget.isZero()
                || remainingBudget.isNegative()) {
            return Optional.empty();
        }
        document.markExtracting();
        String workerToken = document.reserveExtraction(now.plus(EXTRACTION_LEASE));
        return Optional.of(new ExtractionClaim(
                ExtractionTarget.from(document),
                workerToken,
                now.plus(remainingBudget)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean completeExtraction(
            Long documentId,
            String expectedWorkerToken,
            OffsetDateTime processingDeadline,
            String extractedText,
            boolean legacyMockExtraction
    ) {
        setLocalTimeouts();
        Optional<ProductDocument> locked = productDocumentRepository.findByIdForUpdate(documentId);
        if (locked.isEmpty() || productDocumentRepository.hasBatchMembership(documentId)) {
            return false;
        }
        ProductDocument document = locked.get();
        OffsetDateTime now = databaseNow();
        if (!ownsLiveWorkerClaim(document, expectedWorkerToken, now)) {
            return false;
        }
        if (!now.isBefore(processingDeadline)) {
            document.markFailed(
                    TEMPORARY_FAILURE_CODE, TEMPORARY_FAILURE_MESSAGE, true);
            return false;
        }
        if (!legacyMockExtraction && isPdf(document)) {
            throw new TextExtractionException(
                    "PDF extraction cannot complete without page provenance.");
        }
        document.markReady(extractedText);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean completeExtraction(
            Long documentId,
            String expectedWorkerToken,
            OffsetDateTime processingDeadline,
            DocumentExtractionResult result,
            boolean legacyMockExtraction
    ) {
        setLocalTimeouts();
        Optional<ProductDocument> locked = productDocumentRepository.findByIdForUpdate(documentId);
        if (locked.isEmpty() || productDocumentRepository.hasBatchMembership(documentId)) {
            return false;
        }
        ProductDocument document = locked.get();
        OffsetDateTime now = databaseNow();
        if (!ownsLiveWorkerClaim(document, expectedWorkerToken, now)) {
            return false;
        }
        if (!now.isBefore(processingDeadline)) {
            document.markFailed(
                    TEMPORARY_FAILURE_CODE, TEMPORARY_FAILURE_MESSAGE, true);
            return false;
        }
        persistResult(document, result, legacyMockExtraction);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failExtraction(
            Long documentId,
            String expectedWorkerToken,
            String errorCode,
            String publicMessage,
            boolean retryable
    ) {
        setLocalTimeouts();
        Optional<ProductDocument> locked = productDocumentRepository.findByIdForUpdate(documentId);
        if (locked.isEmpty() || productDocumentRepository.hasBatchMembership(documentId)) {
            return false;
        }
        ProductDocument document = locked.get();
        OffsetDateTime now = databaseNow();
        if (!ownsLiveWorkerClaim(document, expectedWorkerToken, now)) {
            return false;
        }
        document.markFailed(errorCode, publicMessage, retryable);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recoverExpiredExtraction(Long documentId, String expectedToken) {
        setLocalTimeouts();
        Optional<ProductDocument> locked = productDocumentRepository.findByIdForUpdate(documentId);
        if (locked.isEmpty() || productDocumentRepository.hasBatchMembership(documentId)) {
            return false;
        }
        ProductDocument document = locked.get();
        OffsetDateTime now = databaseNow();
        if ((document.getExtractStatus() != ExtractStatus.UPLOADED
                && document.getExtractStatus() != ExtractStatus.EXTRACTING)
                || expectedToken == null
                || !expectedToken.equals(document.getExtractionToken())
                || document.getExtractionLeaseUntil() == null
                || document.getExtractionLeaseUntil().isAfter(now)) {
            return false;
        }
        document.markFailed(TEMPORARY_FAILURE_CODE, TEMPORARY_FAILURE_MESSAGE, true);
        return true;
    }

    private static boolean ownsLiveWorkerClaim(
            ProductDocument document,
            String expectedWorkerToken,
            OffsetDateTime now
    ) {
        return document.getExtractStatus() == ExtractStatus.EXTRACTING
                && document.ownsExtraction(expectedWorkerToken, now);
    }

    private static void requirePositiveBudget(Duration processingBudget) {
        if (processingBudget == null
                || processingBudget.isZero()
                || processingBudget.isNegative()) {
            throw new IllegalArgumentException("processingBudget must be positive");
        }
        if (processingBudget.compareTo(
                EXTRACTION_LEASE.minus(EXTRACTION_LEASE_CLEANUP_MARGIN)) > 0) {
            throw new IllegalArgumentException(
                    "processingBudget must not exceed the extraction lease "
                            + "minus the 30s cleanup margin");
        }
    }

    private void setLocalTimeouts() {
        entityManager.createNativeQuery(
                        "select set_config('lock_timeout', :timeout, true)")
                .setParameter("timeout", FAILURE_LOCK_TIMEOUT)
                .getSingleResult();
        entityManager.createNativeQuery(
                        "select set_config('statement_timeout', :timeout, true)")
                .setParameter("timeout", FAILURE_STATEMENT_TIMEOUT)
                .getSingleResult();
    }

    private OffsetDateTime databaseNow() {
        return (OffsetDateTime) entityManager
                .createNativeQuery("select clock_timestamp()", OffsetDateTime.class)
                .getSingleResult();
    }

    record ExtractionClaim(
            ExtractionTarget target,
            String workerToken,
            OffsetDateTime processingDeadline
    ) {
    }

    private void persistResult(
            ProductDocument document,
            DocumentExtractionResult result,
            boolean legacyMockExtraction
    ) {
        if (!result.hasPageResults()) {
            if (!legacyMockExtraction && isPdf(document)) {
                throw new TextExtractionException(
                        "PDF extraction cannot complete without page provenance.");
            }
            document.markReady(result.text());
            return;
        }

        Number maximumGeneration = (Number) entityManager.createQuery("""
                        select coalesce(max(run.runGeneration), 0)
                        from DocumentExtractionRun run
                        where run.productDocument.id = :documentId
                        """)
                .setParameter("documentId", document.getId())
                .getSingleResult();
        int generation = Math.addExact(maximumGeneration.intValue(), 1);
        DocumentExtractionRun run = DocumentExtractionRun.succeeded(
                document,
                generation,
                result.sourceHash(),
                EXTRACTION_CONFIG_VERSION,
                Map.of(
                        "routing", "pdfbox-first-per-page",
                        "minimumTextCodepoints", 32,
                        "minimumReadableRatio", 0.70d,
                        "renderDpi", 300,
                        "ocrLanguage", "kor+eng"),
                result.textHash(),
                result.pages().size());
        entityManager.persist(run);
        for (PageExtractionResult page : result.pages()) {
            entityManager.persist(toEntity(run, page));
        }
        document.markReady(result.text(), run);
    }

    private static boolean isPdf(ProductDocument document) {
        return DocumentMediaType.resolve(document.getMediaType(), document.getFileName())
                .filter(mediaType -> mediaType == DocumentMediaType.PDF)
                .isPresent();
    }

    private static DocumentExtractionPage toEntity(
            DocumentExtractionRun run,
            PageExtractionResult page
    ) {
        if (page.selectedMethod() == PageExtractionMethod.PDFBOX_TEXT) {
            return DocumentExtractionPage.pdfBoxText(
                    run,
                    page.pageNumber(),
                    page.selectedText(),
                    page.selectedTextHash(),
                    page.pdfboxCandidateHash());
        }
        return DocumentExtractionPage.ocrKorEng(
                run,
                page.pageNumber(),
                page.selectedText(),
                page.selectedTextHash(),
                page.pdfboxCandidateHash(),
                page.ocrRenderArtifactKey(),
                page.ocrRenderArtifactHash(),
                page.ocrConfigSnapshot(),
                page.ocrEngine().name(),
                page.ocrEngine().version()
                        + " (tessdata " + page.ocrEngine().tessdataVersion() + ")",
                page.ocrEngine().language(),
                page.ocrConfidence(),
                page.ocrWarnings());
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean completeBatchExtraction(
            Long itemId,
            String workerOwner,
            long leaseFence,
            OffsetDateTime finishedAt,
            DocumentExtractionResult result
    ) {
        if (batchClaimRepository.lockClaimBatch(itemId, workerOwner, leaseFence).isEmpty()) {
            return false;
        }
        Optional<Long> documentId = batchClaimRepository.lockCompletableClaimDocument(
                itemId, workerOwner, leaseFence, finishedAt);
        if (documentId.isEmpty()) {
            return batchClaimRepository.completeClaim(
                    itemId, workerOwner, leaseFence, finishedAt, result.text()) == 1;
        }

        ProductDocument document = productDocumentRepository.findByIdForUpdate(documentId.get())
                .filter(candidate -> candidate.getExtractStatus() == ExtractStatus.EXTRACTING)
                .orElseThrow(() -> new IllegalStateException(
                        "Completable batch claim has no extracting document. itemId=" + itemId));
        persistResult(document, result, false);

        int affectedRows = batchClaimRepository.completeClaim(
                itemId, workerOwner, leaseFence, finishedAt, result.text());
        if (affectedRows != 1) {
            throw new IllegalStateException(
                    "Locked batch claim became stale during completion. itemId=" + itemId);
        }
        return true;
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
