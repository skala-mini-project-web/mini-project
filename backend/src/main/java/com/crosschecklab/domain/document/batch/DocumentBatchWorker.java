package com.crosschecklab.domain.document.batch;

import com.crosschecklab.domain.document.DocumentExtractionTransitions;
import com.crosschecklab.domain.document.DocumentMediaType;
import com.crosschecklab.domain.document.extraction.DocumentExtractionResult;
import com.crosschecklab.domain.document.extraction.ExtractionTarget;
import com.crosschecklab.domain.document.extraction.TextExtractionException;
import com.crosschecklab.domain.document.extraction.TextExtractionService;
import com.crosschecklab.global.config.AsyncConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DocumentBatchWorker {

    private static final int MAX_ITEMS_PER_WAKE_UP = 4;
    private static final Duration LEASE_DURATION = Duration.ofMinutes(10);
    private static final Duration LEASE_CLEANUP_MARGIN = Duration.ofSeconds(30);
    private static final Duration BASE_RETRY_DELAY = Duration.ofSeconds(15);
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(10);

    private static final String EXTRACTION_FAILED_CODE = "DOCUMENT_EXTRACTION_FAILED";
    private static final String EXTRACTION_FAILED_MESSAGE =
            "문서에서 텍스트를 추출하지 못했습니다.";
    private static final String TEMPORARY_FAILURE_CODE = "DOCUMENT_EXTRACTION_TEMPORARY_FAILURE";
    private static final String TEMPORARY_FAILURE_MESSAGE =
            "문서 추출 중 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.";
    private static final String QUARANTINE_REASON =
            "Document extraction failed permanently or exhausted its attempts.";
    private static final String PHASE_BEGIN = "begin";
    private static final String PHASE_COMPLETE = "complete";

    private final DocumentBatchClaimRepository claimRepository;
    private final DocumentExtractionTransitions transitions;
    private final TextExtractionService textExtractionService;
    private final Executor executor;
    private final ExecutorService extractionExecutor;
    private final Duration extractionTimeout;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final String workerOwner = "document-batch-" + UUID.randomUUID();

    @Autowired
    public DocumentBatchWorker(
            DocumentBatchClaimRepository claimRepository,
            DocumentExtractionTransitions transitions,
            TextExtractionService textExtractionService,
            @Qualifier(AsyncConfig.ANALYSIS_EXECUTOR) Executor executor,
            Clock clock,
            @Value("${document-batch.extraction-timeout}") Duration extractionTimeout,
            @Value("${ocr.request-timeout}") Duration ocrRequestTimeout
    ) {
        validateRuntimeBudgets(extractionTimeout, ocrRequestTimeout);
        this.claimRepository = claimRepository;
        this.transitions = transitions;
        this.textExtractionService = textExtractionService;
        this.executor = executor;
        this.clock = clock;
        this.extractionExecutor = newExtractionExecutor();
        this.extractionTimeout = extractionTimeout;
    }

    public DocumentBatchWorker(
            DocumentBatchClaimRepository claimRepository,
            DocumentExtractionTransitions transitions,
            TextExtractionService textExtractionService,
            Executor executor,
            Clock clock,
            ExecutorService extractionExecutor,
            Duration extractionTimeout
    ) {
        validateExtractionTimeout(extractionTimeout);
        this.claimRepository = claimRepository;
        this.transitions = transitions;
        this.textExtractionService = textExtractionService;
        this.executor = executor;
        this.clock = clock;
        this.extractionExecutor = extractionExecutor;
        this.extractionTimeout = extractionTimeout;
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
        Duration maximumTimeout = LEASE_DURATION.minus(LEASE_CLEANUP_MARGIN);
        if (extractionTimeout.compareTo(maximumTimeout) > 0) {
            throw new IllegalArgumentException(
                    "document-batch.extraction-timeout must not exceed "
                            + "the lease duration minus the 30s cleanup margin");
        }
    }

    /**
     * Wakes one bounded worker pass. Repeated scheduler ticks cannot build an in-memory queue or run
     * concurrent passes in this process; PostgreSQL remains the only owner of pending work.
     */
    public void wakeUp() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    runBoundedPass();
                } catch (RuntimeException e) {
                    log.error("Document batch worker pass failed", e);
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
    }

    private void runBoundedPass() {
        for (int processed = 0; processed < MAX_ITEMS_PER_WAKE_UP; processed++) {
            OffsetDateTime claimedAt = OffsetDateTime.now(clock);
            List<DocumentBatchItem> claimed = claimRepository.claimDue(
                    workerOwner,
                    claimedAt,
                    claimedAt.plus(LEASE_DURATION),
                    1);
            if (claimed.isEmpty()) {
                return;
            }
            DocumentBatchItem item = claimed.getFirst();
            log.info("Document batch item claimed. itemId={}, batchId={}, owner={}, fence={}, attempt={}",
                    item.getId(), item.getBatch().getId(), workerOwner, item.getLeaseFence(),
                    item.getAttemptCount());
            execute(item);
        }
    }

    private void execute(DocumentBatchItem item) {
        long leaseFence = item.getLeaseFence();
        Long batchId = item.getBatch().getId();
        int attempt = item.getAttemptCount();
        Future<?> lifecycle;
        try {
            lifecycle = extractionExecutor.submit(() -> executeLifecycle(item, leaseFence));
        } catch (RuntimeException e) {
            log.error("Batch document extraction lifecycle could not start. "
                            + "itemId={}, batchId={}, owner={}, fence={}, attempt={}",
                    item.getId(), batchId, workerOwner, leaseFence, attempt, e);
            finishFailure(
                    item,
                    leaseFence,
                    true,
                    TEMPORARY_FAILURE_CODE,
                    TEMPORARY_FAILURE_MESSAGE,
                    e);
            return;
        }
        try {
            lifecycle.get(extractionTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            cancelLifecycle(lifecycle);
            log.warn("Batch document extraction timed out. "
                            + "itemId={}, batchId={}, owner={}, fence={}, attempt={}",
                    item.getId(), batchId, workerOwner, leaseFence, attempt);
            try {
                if (!finishFailure(
                        item,
                        leaseFence,
                        true,
                        TEMPORARY_FAILURE_CODE,
                        TEMPORARY_FAILURE_MESSAGE,
                        new BatchExtractionTimeoutException(e))) {
                    log.warn("Timed-out batch extraction fence was no longer current. "
                                    + "itemId={}, batchId={}, owner={}, fence={}, attempt={}",
                            item.getId(), batchId, workerOwner, leaseFence, attempt);
                }
            } catch (RuntimeException transitionFailure) {
                throw new IllegalStateException(
                        "Timed-out batch extraction failure transition did not complete. itemId="
                                + item.getId() + ", owner=" + workerOwner + ", fence=" + leaseFence,
                        transitionFailure);
            }
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelLifecycle(lifecycle);
            BatchExtractionInterruptedException failure =
                    new BatchExtractionInterruptedException(e);
            log.warn("Batch document extraction lifecycle was interrupted. "
                            + "itemId={}, batchId={}, owner={}, fence={}, attempt={}",
                    item.getId(), batchId, workerOwner, leaseFence, attempt);
            finishFailure(
                    item,
                    leaseFence,
                    true,
                    TEMPORARY_FAILURE_CODE,
                    TEMPORARY_FAILURE_MESSAGE,
                    failure);
            return;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TextExtractionException extractionFailure) {
                log.warn("Batch document extraction failed. "
                                + "itemId={}, batchId={}, owner={}, fence={}, attempt={}, exception={}",
                        item.getId(), batchId, workerOwner, leaseFence, attempt,
                        extractionFailure.getClass().getName());
                finishFailure(
                        item,
                        leaseFence,
                        extractionFailure.isRetryable(),
                        EXTRACTION_FAILED_CODE,
                        EXTRACTION_FAILED_MESSAGE,
                        extractionFailure);
                return;
            }
            if (cause instanceof RuntimeException runtimeException) {
                log.error("Unexpected batch document extraction failure. "
                                + "itemId={}, batchId={}, owner={}, fence={}, attempt={}, exception={}",
                        item.getId(), batchId, workerOwner, leaseFence, attempt,
                        runtimeException.getClass().getName());
                finishFailure(
                        item,
                        leaseFence,
                        true,
                        TEMPORARY_FAILURE_CODE,
                        TEMPORARY_FAILURE_MESSAGE,
                        runtimeException);
                return;
            }
            if (cause instanceof Error error) {
                finishFailure(
                        item,
                        leaseFence,
                        true,
                        TEMPORARY_FAILURE_CODE,
                        TEMPORARY_FAILURE_MESSAGE,
                        new BatchExtractionFatalException(error));
                throw error;
            }
            IllegalStateException failure =
                    new IllegalStateException("Batch extraction lifecycle failed", cause);
            log.error("Unexpected batch document extraction failure. "
                            + "itemId={}, batchId={}, owner={}, fence={}, attempt={}, exception={}",
                    item.getId(), batchId, workerOwner, leaseFence, attempt,
                    failure.getClass().getName());
            finishFailure(
                    item,
                    leaseFence,
                    true,
                    TEMPORARY_FAILURE_CODE,
                    TEMPORARY_FAILURE_MESSAGE,
                    failure);
            return;
        } catch (RuntimeException e) {
            log.error("Unexpected batch document extraction failure. "
                            + "itemId={}, batchId={}, owner={}, fence={}, attempt={}, exception={}",
                    item.getId(), batchId, workerOwner, leaseFence, attempt,
                    e.getClass().getName());
            finishFailure(
                    item,
                    leaseFence,
                    true,
                    TEMPORARY_FAILURE_CODE,
                    TEMPORARY_FAILURE_MESSAGE,
                    e);
            return;
        } finally {
            cancelLifecycle(lifecycle);
        }
    }

    private void cancelLifecycle(Future<?> lifecycle) {
        if (!lifecycle.isDone()) {
            lifecycle.cancel(true);
            if (extractionExecutor instanceof ThreadPoolExecutor threadPoolExecutor) {
                threadPoolExecutor.purge();
            }
        }
    }

    private void executeLifecycle(DocumentBatchItem item, long leaseFence) {
        OffsetDateTime startedAt = OffsetDateTime.now(clock);
        Optional<ExtractionTarget> target = transitions.beginBatchExtraction(
                item.getId(), workerOwner, leaseFence, startedAt);
        log.info("Document batch begin result. itemId={}, batchId={}, owner={}, fence={}, "
                        + "attempt={}, targetPresent={}",
                item.getId(), item.getBatch().getId(), workerOwner, leaseFence,
                item.getAttemptCount(), target.isPresent());
        if (target.isEmpty()) {
            finishLifecycleInconsistency(item, leaseFence, PHASE_BEGIN);
            return;
        }

        log.info("Document batch extraction started. itemId={}, batchId={}, owner={}, fence={}, "
                        + "attempt={}",
                item.getId(), item.getBatch().getId(), workerOwner, leaseFence,
                item.getAttemptCount());
        DocumentExtractionResult extractionResult;
        try {
            try {
                extractionResult = textExtractionService.extractResult(target.get());
            } catch (UnsupportedOperationException unsupported) {
                if (isPdf(target.get())) {
                    throw new TextExtractionException(
                            "PDF extractor must provide structured page results.", unsupported);
                }
                String extractedText = textExtractionService.extract(target.get());
                completeBatchExtraction(item, leaseFence, extractedText);
                return;
            }
            if (isPdf(target.get()) && !extractionResult.hasPageResults()) {
                throw new TextExtractionException(
                        "PDF extractor returned no page provenance.");
            }
        } catch (RuntimeException e) {
            log.warn("Document batch extraction threw an exception. "
                            + "itemId={}, batchId={}, owner={}, fence={}, attempt={}, exception={}",
                    item.getId(), item.getBatch().getId(), workerOwner, leaseFence,
                    item.getAttemptCount(), e.getClass().getName());
            throw e;
        }
        log.info("Document batch extraction ended. itemId={}, batchId={}, owner={}, fence={}, "
                        + "attempt={}",
                item.getId(), item.getBatch().getId(), workerOwner, leaseFence,
                item.getAttemptCount());
        OffsetDateTime finishedAt = OffsetDateTime.now(clock);
        boolean completed = transitions.completeBatchExtraction(
                item.getId(), workerOwner, leaseFence, finishedAt, extractionResult);
        log.info("Document batch complete result. itemId={}, batchId={}, owner={}, fence={}, "
                        + "attempt={}, accepted={}",
                item.getId(), item.getBatch().getId(), workerOwner, leaseFence,
                item.getAttemptCount(), completed);
        if (!completed) {
            finishLifecycleInconsistency(item, leaseFence, PHASE_COMPLETE);
        }
    }

    private void completeBatchExtraction(
            DocumentBatchItem item,
            long leaseFence,
            String extractedText
    ) {
        log.info("Document batch extraction ended. itemId={}, batchId={}, owner={}, fence={}, "
                        + "attempt={}",
                item.getId(), item.getBatch().getId(), workerOwner, leaseFence,
                item.getAttemptCount());
        OffsetDateTime finishedAt = OffsetDateTime.now(clock);
        boolean completed = transitions.completeBatchExtraction(
                item.getId(), workerOwner, leaseFence, finishedAt, extractedText);
        log.info("Document batch complete result. itemId={}, batchId={}, owner={}, fence={}, "
                        + "attempt={}, accepted={}",
                item.getId(), item.getBatch().getId(), workerOwner, leaseFence,
                item.getAttemptCount(), completed);
        if (!completed) {
            finishLifecycleInconsistency(item, leaseFence, PHASE_COMPLETE);
        }
    }

    private static boolean isPdf(ExtractionTarget target) {
        return DocumentMediaType.resolve(target.mediaType(), target.fileName())
                .filter(mediaType -> mediaType == DocumentMediaType.PDF)
                .isPresent();
    }

    void shutdown() {
        extractionExecutor.shutdownNow();
    }

    private boolean finishFailure(
            DocumentBatchItem item,
            long leaseFence,
            boolean retryable,
            String errorCode,
            String errorMessage,
            RuntimeException failure
    ) {
        OffsetDateTime finishedAt = OffsetDateTime.now(clock);
        OffsetDateTime nextDueAt = finishedAt.plus(retryDelay(item.getAttemptCount()));
        boolean accepted = transitions.failBatchExtraction(
                item.getId(),
                workerOwner,
                leaseFence,
                finishedAt,
                nextDueAt,
                retryable,
                errorCode,
                errorMessage,
                QUARANTINE_REASON,
                truncate(failure.getClass().getName(), 500));
        if (!accepted) {
            log.info("Discarded stale batch extraction failure. "
                            + "itemId={}, batchId={}, owner={}, fence={}, attempt={}",
                    item.getId(), item.getBatch().getId(), workerOwner, leaseFence,
                    item.getAttemptCount());
        }
        log.info("Document batch failure result. itemId={}, batchId={}, owner={}, fence={}, "
                        + "attempt={}, accepted={}",
                item.getId(), item.getBatch().getId(), workerOwner, leaseFence,
                item.getAttemptCount(), accepted);
        return accepted;
    }

    private void finishLifecycleInconsistency(
            DocumentBatchItem item,
            long leaseFence,
            String phase
    ) {
        BatchLifecycleInconsistencyException failure =
                new BatchLifecycleInconsistencyException(phase);
        boolean accepted = finishFailure(
                item,
                leaseFence,
                true,
                TEMPORARY_FAILURE_CODE,
                TEMPORARY_FAILURE_MESSAGE,
                failure);
        if (accepted) {
            log.error("Batch extraction lifecycle returned an inconsistent result; "
                            + "the fenced failure transition closed the claim. "
                            + "itemId={}, owner={}, fence={}, phase={}",
                    item.getId(), workerOwner, leaseFence, phase);
        } else {
            log.info("Batch extraction lifecycle result belonged to a cancelled or stale claim. "
                            + "itemId={}, owner={}, fence={}, phase={}",
                    item.getId(), workerOwner, leaseFence, phase);
        }
    }

    private Duration retryDelay(int attemptCount) {
        long multiplier = 1L << Math.min(Math.max(attemptCount - 1, 0), 30);
        long seconds = Math.min(MAX_RETRY_DELAY.toSeconds(),
                BASE_RETRY_DELAY.toSeconds() * multiplier);
        return Duration.ofSeconds(seconds);
    }

    private String truncate(String value, int maximumLength) {
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    private static ExecutorService newExtractionExecutor() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "document-batch-extraction");
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                MAX_ITEMS_PER_WAKE_UP,
                MAX_ITEMS_PER_WAKE_UP,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_ITEMS_PER_WAKE_UP),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static final class BatchExtractionTimeoutException extends RuntimeException {

        private BatchExtractionTimeoutException(TimeoutException cause) {
            super("Batch document extraction timed out", cause);
        }
    }

    private static final class BatchExtractionInterruptedException extends RuntimeException {

        private BatchExtractionInterruptedException(InterruptedException cause) {
            super("Batch document extraction was interrupted", cause);
        }
    }

    private static final class BatchLifecycleInconsistencyException extends RuntimeException {

        private BatchLifecycleInconsistencyException(String phase) {
            super("Batch extraction lifecycle returned an empty result in phase " + phase);
        }
    }

    private static final class BatchExtractionFatalException extends RuntimeException {

        private BatchExtractionFatalException(Error cause) {
            super("Batch extraction lifecycle terminated with an error", cause);
        }
    }
}
