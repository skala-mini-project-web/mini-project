package com.crosschecklab.domain.document;

import com.crosschecklab.domain.document.extraction.DocumentExtractionResult;
import com.crosschecklab.domain.document.extraction.ExtractionTarget;
import com.crosschecklab.domain.document.extraction.MockDocumentTextExtractor;
import com.crosschecklab.domain.document.extraction.OcrClient;
import com.crosschecklab.domain.document.extraction.TextExtractionException;
import com.crosschecklab.domain.document.extraction.TextExtractionService;
import com.crosschecklab.global.config.AsyncConfig;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.aop.support.AopUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// DOC-002. UPLOADED 상태의 문서를 백그라운드에서 EXTRACTING → READY/FAILED 로 옮긴다.
// 업로드 응답(202)은 이 작업을 기다리지 않으며, 클라이언트는 statusUrl 을 폴링해 결과를 확인한다.
@Slf4j
@Component
public class DocumentExtractionRunner {

    private static final int MAX_CONCURRENT_EXTRACTIONS = 4;
    private static final int RECOVERY_BATCH_SIZE = 32;
    private static final Duration LEASE_DURATION = Duration.ofMinutes(10);
    private static final Duration LEASE_CLEANUP_MARGIN = Duration.ofSeconds(30);
    private static final String EXTRACTION_FAILED_CODE = "DOCUMENT_EXTRACTION_FAILED";
    private static final String EXTRACTION_FAILED_MESSAGE = "문서에서 텍스트를 추출하지 못했습니다.";
    private static final String TEMPORARY_FAILURE_CODE = "DOCUMENT_EXTRACTION_TEMPORARY_FAILURE";
    private static final String TEMPORARY_FAILURE_MESSAGE =
            "문서 추출 중 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.";

    private final DocumentExtractionTransitions transitions;
    private final ProductDocumentRepository productDocumentRepository;
    private final TextExtractionService textExtractionService;
    private final ExecutorService extractionExecutor;
    private final Duration extractionTimeout;

    @Autowired
    public DocumentExtractionRunner(
            DocumentExtractionTransitions transitions,
            ProductDocumentRepository productDocumentRepository,
            TextExtractionService textExtractionService,
            @Value("${document-batch.extraction-timeout}") Duration extractionTimeout,
            @Value("${ocr.request-timeout}") Duration ocrRequestTimeout
    ) {
        validateRuntimeBudgets(extractionTimeout, ocrRequestTimeout);
        this.transitions = transitions;
        this.productDocumentRepository = productDocumentRepository;
        this.textExtractionService = textExtractionService;
        this.extractionExecutor = newExtractionExecutor();
        this.extractionTimeout = extractionTimeout;
    }

    DocumentExtractionRunner(
            DocumentExtractionTransitions transitions,
            ProductDocumentRepository productDocumentRepository,
            TextExtractionService textExtractionService,
            ExecutorService extractionExecutor,
            Duration extractionTimeout
    ) {
        validateExtractionTimeout(extractionTimeout);
        this.transitions = transitions;
        this.productDocumentRepository = productDocumentRepository;
        this.textExtractionService = textExtractionService;
        this.extractionExecutor = extractionExecutor;
        this.extractionTimeout = extractionTimeout;
    }

    // 요청 트랜잭션이 커밋된 뒤에 실행한다. 커밋 전이면 새 트랜잭션에서 문서를 찾지 못한다.
    @Async(AsyncConfig.ANALYSIS_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onExtractionRequested(DocumentExtractionRequestedEvent event) {
        run(event.documentId(), event.expectedRequestToken());
    }

    public void run(Long documentId, String expectedRequestToken) {
        long lifecycleStarted = System.nanoTime();
        Optional<DocumentExtractionTransitions.ExtractionClaim> claimed;
        try {
            claimed = transitions.claimExtraction(
                    documentId,
                    expectedRequestToken,
                    remainingBudget(lifecycleStarted));
        } catch (RuntimeException e) {
            log.error("문서 {} 추출 claim 실패", documentId, e);
            return;
        }
        if (claimed.isEmpty()) {
            log.warn("추출을 시작할 문서가 없습니다. documentId={}", documentId);
            return;
        }
        DocumentExtractionTransitions.ExtractionClaim claim = claimed.get();
        ExtractionTarget extractionTarget = claim.target();
        boolean pdf = DocumentMediaType.resolve(
                        extractionTarget.mediaType(), extractionTarget.fileName())
                .filter(mediaType -> mediaType == DocumentMediaType.PDF)
                .isPresent();
        boolean legacyMockExtraction = MockDocumentTextExtractor.class.isAssignableFrom(
                AopUtils.getTargetClass(textExtractionService));

        Future<ExtractionOutput> extraction;
        try {
            extraction = extractionExecutor.submit(
                    () -> extract(extractionTarget, legacyMockExtraction, pdf));
        } catch (RuntimeException e) {
            log.error("문서 {} 추출 작업 제출 실패", documentId, e);
            fail(documentId, claim.workerToken(), true, true);
            return;
        }

        try {
            ExtractionOutput output = extraction.get(
                    remainingBudget(lifecycleStarted).toNanos(), TimeUnit.NANOSECONDS);
            boolean published = output.result() == null
                    ? transitions.completeExtraction(
                            documentId,
                            claim.workerToken(),
                            claim.processingDeadline(),
                            output.text(),
                            legacyMockExtraction)
                    : transitions.completeExtraction(
                            documentId,
                            claim.workerToken(),
                            claim.processingDeadline(),
                            output.result(),
                            legacyMockExtraction);
            if (published) {
                log.info("문서 {} 추출 완료 ({}자)", documentId, output.text().length());
            } else {
                log.info("문서 {} stale/late 추출 결과 폐기", documentId);
            }
        } catch (TimeoutException e) {
            extraction.cancel(true);
            purgeCancelledTasks();
            log.warn("문서 {} 추출 시간 초과", documentId);
            fail(documentId, claim.workerToken(), true, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            extraction.cancel(true);
            purgeCancelledTasks();
            log.warn("문서 {} 추출 대기 중 인터럽트", documentId);
            fail(documentId, claim.workerToken(), true, true);
        } catch (ExecutionException e) {
            handleExtractionFailure(documentId, claim.workerToken(), e.getCause());
        } catch (RuntimeException e) {
            log.error("문서 {} 추출 확정 중 예기치 않은 오류", documentId, e);
            fail(documentId, claim.workerToken(), true, true);
        } finally {
            if (!extraction.isDone()) {
                extraction.cancel(true);
                purgeCancelledTasks();
            }
        }
    }

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT30S")
    public void recoverExpiredExtractions() {
        List<ProductDocumentRepository.ExpiredExtractionCandidate> expired =
                productDocumentRepository.findExpiredExtractionCandidates(
                        PageRequest.of(0, RECOVERY_BATCH_SIZE));
        for (ProductDocumentRepository.ExpiredExtractionCandidate candidate : expired) {
            try {
                if (transitions.recoverExpiredExtraction(
                        candidate.getId(), candidate.getExtractionToken())) {
                    log.warn("만료된 문서 추출 claim 복구. documentId={}", candidate.getId());
                }
            } catch (RuntimeException e) {
                log.error("만료된 문서 추출 claim 복구 실패. documentId={}, token={}",
                        candidate.getId(), candidate.getExtractionToken(), e);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        extractionExecutor.shutdownNow();
    }

    private ExtractionOutput extract(
            ExtractionTarget target,
            boolean legacyMockExtraction,
            boolean pdf
    ) {
        try {
            DocumentExtractionResult result = textExtractionService.extractResult(target);
            if (!legacyMockExtraction && pdf && !result.hasPageResults()) {
                throw new TextExtractionException("PDF extractor returned no page provenance.");
            }
            return new ExtractionOutput(result.text(), result);
        } catch (UnsupportedOperationException unsupported) {
            if (!legacyMockExtraction && pdf) {
                throw new TextExtractionException(
                        "PDF extractor must provide structured page results.", unsupported);
            }
            return new ExtractionOutput(textExtractionService.extract(target), null);
        }
    }

    private void handleExtractionFailure(
            Long documentId,
            String workerToken,
            Throwable failure
    ) {
        if (failure instanceof OcrClient.OcrException ocrFailure) {
            log.warn("문서 {} OCR 실패. kind={}", documentId, ocrFailure.kind(), ocrFailure);
            fail(documentId, workerToken, ocrFailure.retryable(), false);
        } else if (failure instanceof TextExtractionException extractionFailure) {
            log.warn("문서 {} 추출 실패", documentId, extractionFailure);
            fail(documentId, workerToken, extractionFailure.isRetryable(), false);
        } else {
            log.error("문서 {} 추출 중 예기치 않은 오류", documentId, failure);
            fail(documentId, workerToken, true, true);
        }
    }

    private void fail(Long documentId, String workerToken, boolean retryable, boolean temporary) {
        try {
            boolean published = transitions.failExtraction(
                    documentId,
                    workerToken,
                    temporary ? TEMPORARY_FAILURE_CODE : EXTRACTION_FAILED_CODE,
                    temporary ? TEMPORARY_FAILURE_MESSAGE : EXTRACTION_FAILED_MESSAGE,
                    retryable);
            if (!published) {
                log.info("문서 {} stale 추출 실패 폐기", documentId);
            }
        } catch (RuntimeException transitionFailure) {
            log.error("문서 {} 추출 실패 확정 실패", documentId, transitionFailure);
        }
    }

    private Duration remainingBudget(long lifecycleStarted) {
        Duration elapsed = Duration.ofNanos(Math.max(0L, System.nanoTime() - lifecycleStarted));
        Duration remaining = extractionTimeout.minus(elapsed);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new ExtractionLifecycleTimeoutException();
        }
        return remaining;
    }

    private void purgeCancelledTasks() {
        if (extractionExecutor instanceof ThreadPoolExecutor threadPoolExecutor) {
            threadPoolExecutor.purge();
        }
    }

    private static void validateRuntimeBudgets(
            Duration extractionTimeout,
            Duration ocrRequestTimeout
    ) {
        validateExtractionTimeout(extractionTimeout);
        if (ocrRequestTimeout == null
                || ocrRequestTimeout.isZero()
                || ocrRequestTimeout.isNegative()) {
            throw new IllegalArgumentException("ocr.request-timeout must be positive");
        }
        if (ocrRequestTimeout.compareTo(extractionTimeout) >= 0) {
            throw new IllegalArgumentException(
                    "ocr.request-timeout must be shorter than "
                            + "document-batch.extraction-timeout");
        }
    }

    private static void validateExtractionTimeout(Duration extractionTimeout) {
        if (extractionTimeout == null
                || extractionTimeout.isZero()
                || extractionTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "document-batch.extraction-timeout must be positive");
        }
        if (extractionTimeout.compareTo(LEASE_DURATION.minus(LEASE_CLEANUP_MARGIN)) > 0) {
            throw new IllegalArgumentException(
                    "document-batch.extraction-timeout must not exceed "
                            + "the lease duration minus the 30s cleanup margin");
        }
    }

    private static ExecutorService newExtractionExecutor() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "document-extraction");
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                MAX_CONCURRENT_EXTRACTIONS,
                MAX_CONCURRENT_EXTRACTIONS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_CONCURRENT_EXTRACTIONS),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    private record ExtractionOutput(String text, DocumentExtractionResult result) {
    }

    private static final class ExtractionLifecycleTimeoutException extends RuntimeException {
    }
}
