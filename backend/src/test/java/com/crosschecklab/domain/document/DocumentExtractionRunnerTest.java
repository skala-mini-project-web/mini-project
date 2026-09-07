package com.crosschecklab.domain.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crosschecklab.domain.document.batch.DocumentBatchClaimRepository;
import com.crosschecklab.domain.document.batch.DocumentBatchItem;
import com.crosschecklab.domain.document.batch.DocumentBatchWorker;
import com.crosschecklab.domain.document.extraction.DocumentExtractionResult;
import com.crosschecklab.domain.document.extraction.ExtractionTarget;
import com.crosschecklab.domain.document.extraction.PageExtractionResult;
import com.crosschecklab.domain.document.extraction.PdfBoxTextExtractor;
import com.crosschecklab.domain.document.extraction.TextExtractionException;
import com.crosschecklab.domain.document.extraction.TextExtractionService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DocumentExtractionRunnerTest {

    private static final byte[] MALFORMED_PDF =
            "%PDF-1.7\n% synthetic malformed fixture\n".getBytes(StandardCharsets.US_ASCII);
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    @Test
    void malformedSyntheticPdfIsANonRetryableExtractionFailure() {
        PdfBoxTextExtractor extractor = new PdfBoxTextExtractor();

        assertThat(MALFORMED_PDF).hasSize(39);
        assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(MALFORMED_PDF)))
                .isInstanceOfSatisfying(TextExtractionException.class,
                        failure -> assertThat(failure.isRetryable()).isFalse());
    }

    @Test
    void batchWorkerFinishesMalformedPdfAttemptAsNonRetryableFailure() {
        DocumentBatchClaimRepository claims = mock(DocumentBatchClaimRepository.class);
        DocumentExtractionTransitions transitions = mock(DocumentExtractionTransitions.class);
        TextExtractionService extractionService = mock(TextExtractionService.class);
        DocumentBatchItem item = mock(DocumentBatchItem.class, RETURNS_DEEP_STUBS);
        ExtractionTarget target =
                new ExtractionTarget(41L, "malformed.pdf", "application/pdf", "sha256://malformed");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        when(item.getId()).thenReturn(17L);
        when(item.getLeaseFence()).thenReturn(3L);
        when(item.getAttemptCount()).thenReturn(1);
        when(claims.claimDue(anyString(), any(), any(), eq(1)))
                .thenReturn(List.of(item))
                .thenReturn(List.of());
        when(transitions.beginBatchExtraction(eq(17L), anyString(), eq(3L), any()))
                .thenReturn(Optional.of(target));
        when(extractionService.extractResult(target))
                .thenThrow(new TextExtractionException("PDF 텍스트 추출에 실패했습니다."));
        when(transitions.failBatchExtraction(
                eq(17L),
                anyString(),
                eq(3L),
                any(),
                any(),
                eq(false),
                eq("DOCUMENT_EXTRACTION_FAILED"),
                eq("문서에서 텍스트를 추출하지 못했습니다."),
                anyString(),
                anyString()))
                .thenReturn(true);

        DocumentBatchWorker worker = new DocumentBatchWorker(
                claims, transitions, extractionService, Runnable::run, clock);

        worker.wakeUp();

        ArgumentCaptor<OffsetDateTime> finishedAt = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(transitions, timeout(1_000)).failBatchExtraction(
                eq(17L),
                anyString(),
                eq(3L),
                finishedAt.capture(),
                any(),
                eq(false),
                eq("DOCUMENT_EXTRACTION_FAILED"),
                eq("문서에서 텍스트를 추출하지 못했습니다."),
                eq("Document extraction failed permanently or exhausted its attempts."),
                eq(TextExtractionException.class.getName()));
        assertThat(finishedAt.getValue().toInstant()).isEqualTo(NOW);
    }

    @Test
    void batchWorkerRejectsPdfResultWithoutPageProvenance() {
        DocumentBatchClaimRepository claims = mock(DocumentBatchClaimRepository.class);
        DocumentExtractionTransitions transitions = mock(DocumentExtractionTransitions.class);
        TextExtractionService extractionService = mock(TextExtractionService.class);
        DocumentBatchItem item = mock(DocumentBatchItem.class, RETURNS_DEEP_STUBS);
        ExtractionTarget target =
                new ExtractionTarget(44L, "missing-pages.pdf", "application/pdf", "sha256://missing");
        DocumentExtractionResult result =
                DocumentExtractionResult.withoutPageProvenance("text", "a".repeat(64));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        when(item.getId()).thenReturn(20L);
        when(item.getLeaseFence()).thenReturn(6L);
        when(item.getAttemptCount()).thenReturn(1);
        when(claims.claimDue(anyString(), any(), any(), eq(1)))
                .thenReturn(List.of(item))
                .thenReturn(List.of());
        when(transitions.beginBatchExtraction(eq(20L), anyString(), eq(6L), any()))
                .thenReturn(Optional.of(target));
        when(extractionService.extractResult(target)).thenReturn(result);
        when(transitions.failBatchExtraction(
                eq(20L),
                anyString(),
                eq(6L),
                any(),
                any(),
                eq(false),
                eq("DOCUMENT_EXTRACTION_FAILED"),
                eq("문서에서 텍스트를 추출하지 못했습니다."),
                anyString(),
                anyString()))
                .thenReturn(true);

        DocumentBatchWorker worker = new DocumentBatchWorker(
                claims, transitions, extractionService, Runnable::run, clock);

        worker.wakeUp();

        verify(transitions, timeout(1_000)).failBatchExtraction(
                eq(20L),
                anyString(),
                eq(6L),
                any(),
                any(),
                eq(false),
                eq("DOCUMENT_EXTRACTION_FAILED"),
                eq("문서에서 텍스트를 추출하지 못했습니다."),
                eq("Document extraction failed permanently or exhausted its attempts."),
                eq(TextExtractionException.class.getName()));
        verify(transitions, never()).completeBatchExtraction(
                eq(20L), anyString(), eq(6L), any(), eq(result));
    }

    @Test
    void successTransitionFailureAlsoFinishesTheFencedAttempt() {
        DocumentBatchClaimRepository claims = mock(DocumentBatchClaimRepository.class);
        DocumentExtractionTransitions transitions = mock(DocumentExtractionTransitions.class);
        TextExtractionService extractionService = mock(TextExtractionService.class);
        DocumentBatchItem item = mock(DocumentBatchItem.class, RETURNS_DEEP_STUBS);
        ExtractionTarget target =
                new ExtractionTarget(42L, "valid.pdf", "application/pdf", "sha256://valid");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        when(item.getId()).thenReturn(18L);
        when(item.getLeaseFence()).thenReturn(4L);
        when(item.getAttemptCount()).thenReturn(1);
        when(claims.claimDue(anyString(), any(), any(), eq(1)))
                .thenReturn(List.of(item))
                .thenReturn(List.of());
        when(transitions.beginBatchExtraction(eq(18L), anyString(), eq(4L), any()))
                .thenReturn(Optional.of(target));
        DocumentExtractionResult result = structuredResult("extracted");
        when(extractionService.extractResult(target)).thenReturn(result);
        when(transitions.completeBatchExtraction(eq(18L), anyString(), eq(4L), any(), eq(result)))
                .thenThrow(new IllegalStateException("success transition failed"));
        when(transitions.failBatchExtraction(
                eq(18L),
                anyString(),
                eq(4L),
                any(),
                any(),
                eq(true),
                eq("DOCUMENT_EXTRACTION_TEMPORARY_FAILURE"),
                eq("문서 추출 중 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
                anyString(),
                anyString()))
                .thenReturn(true);

        DocumentBatchWorker worker = new DocumentBatchWorker(
                claims, transitions, extractionService, Runnable::run, clock);

        worker.wakeUp();

        verify(transitions, timeout(1_000)).failBatchExtraction(
                eq(18L),
                anyString(),
                eq(4L),
                any(),
                any(),
                eq(true),
                eq("DOCUMENT_EXTRACTION_TEMPORARY_FAILURE"),
                eq("문서 추출 중 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
                eq("Document extraction failed permanently or exhausted its attempts."),
                eq(IllegalStateException.class.getName()));
    }

    @Test
    void timedOutBatchLifecycleBeforeExtractionIsRetryable() throws Exception {
        DocumentBatchClaimRepository claims = mock(DocumentBatchClaimRepository.class);
        DocumentExtractionTransitions transitions = mock(DocumentExtractionTransitions.class);
        TextExtractionService extractionService = mock(TextExtractionService.class);
        DocumentBatchItem item = mock(DocumentBatchItem.class, RETURNS_DEEP_STUBS);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ExecutorService extractionExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch lifecycleStarted = new CountDownLatch(1);
        CountDownLatch lifecycleInterrupted = new CountDownLatch(1);
        CountDownLatch allowLifecycleReturn = new CountDownLatch(1);

        when(item.getId()).thenReturn(19L);
        when(item.getLeaseFence()).thenReturn(5L);
        when(item.getAttemptCount()).thenReturn(1);
        when(claims.claimDue(anyString(), any(), any(), eq(1)))
                .thenReturn(List.of(item))
                .thenReturn(List.of());
        when(transitions.beginBatchExtraction(eq(19L), anyString(), eq(5L), any()))
                .thenAnswer(invocation -> {
                    lifecycleStarted.countDown();
                    try {
                        allowLifecycleReturn.await();
                    } catch (InterruptedException e) {
                        lifecycleInterrupted.countDown();
                        allowLifecycleReturn.await();
                    }
                    return Optional.empty();
                });
        when(transitions.failBatchExtraction(
                eq(19L),
                anyString(),
                eq(5L),
                any(),
                any(),
                eq(true),
                eq("DOCUMENT_EXTRACTION_TEMPORARY_FAILURE"),
                eq("문서 추출 중 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
                anyString(),
                anyString()))
                .thenReturn(true);

        DocumentBatchWorker worker = new DocumentBatchWorker(
                claims,
                transitions,
                extractionService,
                Runnable::run,
                clock,
                extractionExecutor,
                java.time.Duration.ofMillis(250));

        try {
            worker.wakeUp();

            assertThat(lifecycleStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(lifecycleInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
            verify(transitions, timeout(1_000)).failBatchExtraction(
                    eq(19L),
                    anyString(),
                    eq(5L),
                    any(),
                    any(),
                    eq(true),
                    eq("DOCUMENT_EXTRACTION_TEMPORARY_FAILURE"),
                    eq("문서 추출 중 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
                    eq("Document extraction failed permanently or exhausted its attempts."),
                    anyString());
            verify(extractionService, never()).extractResult(any());
        } finally {
            allowLifecycleReturn.countDown();
            extractionExecutor.shutdownNow();
        }
    }

    @Test
    void timedOutBatchExtractionIsRetryableAndCannotPublishALateResult() throws Exception {
        DocumentBatchClaimRepository claims = mock(DocumentBatchClaimRepository.class);
        DocumentExtractionTransitions transitions = mock(DocumentExtractionTransitions.class);
        TextExtractionService extractionService = mock(TextExtractionService.class);
        DocumentBatchItem item = mock(DocumentBatchItem.class, RETURNS_DEEP_STUBS);
        ExtractionTarget target =
                new ExtractionTarget(43L, "hung.pdf", "application/pdf", "sha256://hung");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ExecutorService extractionExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch extractionStarted = new CountDownLatch(1);
        CountDownLatch extractionInterrupted = new CountDownLatch(1);
        CountDownLatch allowLateReturn = new CountDownLatch(1);
        CountDownLatch extractionReturned = new CountDownLatch(1);
        CountDownLatch staleCompletionAttempted = new CountDownLatch(1);

        when(item.getId()).thenReturn(19L);
        when(item.getLeaseFence()).thenReturn(5L);
        when(item.getAttemptCount()).thenReturn(1);
        when(claims.claimDue(anyString(), any(), any(), eq(1)))
                .thenReturn(List.of(item))
                .thenReturn(List.of());
        when(transitions.beginBatchExtraction(eq(19L), anyString(), eq(5L), any()))
                .thenReturn(Optional.of(target));
        DocumentExtractionResult result = structuredResult("late stale text");
        when(extractionService.extractResult(target)).thenAnswer(invocation -> {
            extractionStarted.countDown();
            try {
                allowLateReturn.await();
            } catch (InterruptedException e) {
                extractionInterrupted.countDown();
                allowLateReturn.await();
            }
            extractionReturned.countDown();
            return result;
        });
        when(transitions.completeBatchExtraction(
                eq(19L), anyString(), eq(5L), any(), eq(result)))
                .thenAnswer(invocation -> {
                    staleCompletionAttempted.countDown();
                    return false;
                });
        when(transitions.failBatchExtraction(
                eq(19L),
                anyString(),
                eq(5L),
                any(),
                any(),
                eq(true),
                eq("DOCUMENT_EXTRACTION_TEMPORARY_FAILURE"),
                eq("문서 추출 중 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
                anyString(),
                anyString()))
                .thenReturn(true);

        DocumentBatchWorker worker = new DocumentBatchWorker(
                claims,
                transitions,
                extractionService,
                Runnable::run,
                clock,
                extractionExecutor,
                java.time.Duration.ofMillis(250));

        try {
            worker.wakeUp();

            assertThat(extractionStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(extractionInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
            verify(transitions, timeout(1_000)).failBatchExtraction(
                    eq(19L),
                    anyString(),
                    eq(5L),
                    any(),
                    any(),
                    eq(true),
                    eq("DOCUMENT_EXTRACTION_TEMPORARY_FAILURE"),
                    eq("문서 추출 중 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
                    eq("Document extraction failed permanently or exhausted its attempts."),
                    anyString());

            allowLateReturn.countDown();
            assertThat(extractionReturned.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(staleCompletionAttempted.await(1, TimeUnit.SECONDS)).isTrue();
            verify(transitions).completeBatchExtraction(
                    eq(19L), anyString(), eq(5L), any(), eq(result));
        } finally {
            allowLateReturn.countDown();
            extractionExecutor.shutdownNow();
        }
    }

    private static DocumentExtractionResult structuredResult(String text) {
        String textHash = sha256(text);
        return DocumentExtractionResult.ofPages(
                "a".repeat(64),
                List.of(PageExtractionResult.pdfBox(1, text, textHash)));
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
