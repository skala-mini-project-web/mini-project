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
import com.crosschecklab.domain.document.batch.DocumentBatchRepository;
import com.crosschecklab.domain.document.batch.DocumentBatchStatus;
import com.crosschecklab.domain.document.batch.DocumentBatchWorker;
import com.crosschecklab.domain.document.extraction.ExtractionTarget;
import com.crosschecklab.domain.document.extraction.PdfBoxTextExtractor;
import com.crosschecklab.domain.document.extraction.TextExtractionException;
import com.crosschecklab.domain.document.extraction.TextExtractionService;
import com.crosschecklab.support.IntegrationTestSupport;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

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
        when(extractionService.extract(target))
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
        when(extractionService.extract(target)).thenReturn("extracted");
        when(transitions.completeBatchExtraction(eq(18L), anyString(), eq(4L), any(), eq("extracted")))
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
            verify(extractionService, never()).extract(any());
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
        when(extractionService.extract(target)).thenAnswer(invocation -> {
            extractionStarted.countDown();
            try {
                allowLateReturn.await();
            } catch (InterruptedException e) {
                extractionInterrupted.countDown();
                allowLateReturn.await();
            }
            extractionReturned.countDown();
            return "late stale text";
        });
        when(transitions.completeBatchExtraction(
                eq(19L), anyString(), eq(5L), any(), eq("late stale text")))
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
                    eq(19L), anyString(), eq(5L), any(), eq("late stale text"));
        } finally {
            allowLateReturn.countDown();
            extractionExecutor.shutdownNow();
        }
    }
}

class DocumentBatchAggregateIntegrationTest extends IntegrationTestSupport {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-09-07T00:00:00Z");
    private static final String CHECKSUM = "a".repeat(64);
    private static final String WORKER = "aggregate-regression-worker";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DocumentBatchClaimRepository claims;

    @Autowired
    private DocumentBatchRepository batches;

    @Test
    @Transactional
    void successAndQuarantineItemsDurablyQuarantineTheirBatch() {
        Long productId = jdbcTemplate.queryForObject("""
                INSERT INTO products (
                    owner_id, name, product_type, created_at, updated_at
                ) VALUES (1, 'aggregate regression', 'INVESTMENT', ?, ?)
                RETURNING id
                """, Long.class, NOW, NOW);
        Long succeededDocumentId = insertDocument(productId, "succeeded.pdf");
        Long quarantinedDocumentId = insertDocument(productId, "quarantined.pdf");
        Long batchId = jdbcTemplate.queryForObject("""
                INSERT INTO document_batches (
                    product_id, owner_id, scenario, requested_item_count,
                    idempotency_key, manifest_hash, created_at, updated_at
                ) VALUES (?, 1, 'REAL_EXTRACTION', 2, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, productId, "aggregate-regression-" + productId, CHECKSUM, NOW, NOW);
        Long succeededItemId = insertLeasedItem(batchId, productId, succeededDocumentId, 1);
        Long quarantinedItemId = insertLeasedItem(batchId, productId, quarantinedDocumentId, 2);

        assertThat(claims.completeClaim(
                succeededItemId, WORKER, 1L, NOW.plusMinutes(1), "extracted")).isEqualTo(1);
        assertThat(batches.findStatusById(batchId)).contains(DocumentBatchStatus.PENDING);

        assertThat(claims.failClaim(
                quarantinedItemId,
                WORKER,
                1L,
                NOW.plusMinutes(2),
                NOW.plusMinutes(3),
                false,
                "DOCUMENT_EXTRACTION_FAILED",
                "Document extraction failed.",
                "Document extraction failed permanently.",
                "regression")).isEqualTo(1);

        assertThat(batches.findStatusById(batchId)).contains(DocumentBatchStatus.QUARANTINED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT terminal_at = quarantined_at
                       AND terminal_at = ?
                       AND quarantine_reason = 'Document extraction failed permanently.'
                       AND cancelled_at IS NULL
                       AND cancellation_reason IS NULL
                FROM document_batches
                WHERE id = ?
                """, Boolean.class, NOW.plusMinutes(2), batchId)).isTrue();
    }

    @Test
    @Transactional
    void expiredButStillFencedClaimIsDurablyQuarantined() {
        Long productId = jdbcTemplate.queryForObject("""
                INSERT INTO products (
                    owner_id, name, product_type, created_at, updated_at
                ) VALUES (1, 'expired fence regression', 'INVESTMENT', ?, ?)
                RETURNING id
                """, Long.class, NOW, NOW);
        Long documentId = insertDocument(productId, "expired.pdf");
        Long batchId = jdbcTemplate.queryForObject("""
                INSERT INTO document_batches (
                    product_id, owner_id, scenario, requested_item_count,
                    idempotency_key, manifest_hash, created_at, updated_at
                ) VALUES (?, 1, 'REAL_EXTRACTION', 1, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, productId, "expired-fence-" + productId, CHECKSUM, NOW, NOW);
        Long itemId = insertLeasedItem(batchId, productId, documentId, 1);
        jdbcTemplate.update("""
                UPDATE document_batch_items
                SET lease_until = ?
                WHERE id = ?
                """, NOW.plusSeconds(30), itemId);

        assertThat(claims.failClaim(
                itemId,
                WORKER,
                1L,
                NOW.plusMinutes(1),
                NOW.plusMinutes(2),
                false,
                "DOCUMENT_EXTRACTION_FAILED",
                "Document extraction failed.",
                "Expired current claim was closed.",
                "expired-fence-regression")).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT i.status = 'QUARANTINED'
                       AND i.lease_owner IS NULL
                       AND i.lease_until IS NULL
                       AND b.status = 'QUARANTINED'
                       AND b.quarantined_at = ?
                       AND a.outcome = 'QUARANTINED'
                       AND a.ended_at = ?
                FROM document_batch_items i
                JOIN document_batches b ON b.id = i.batch_id
                JOIN document_batch_item_attempts a
                  ON a.item_id = i.id AND a.attempt_no = i.attempt_count
                WHERE i.id = ?
                """, Boolean.class, NOW.plusMinutes(1), NOW.plusMinutes(1), itemId)).isTrue();
    }

    private Long insertDocument(Long productId, String fileName) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO product_documents (
                    product_id, file_name, media_type, file_size, checksum,
                    storage_key, extract_status, confirmed, created_at, updated_at
                ) VALUES (?, ?, 'application/pdf', 1, ?, ?, 'UPLOADED', FALSE, ?, ?)
                RETURNING id
                """, Long.class, productId, fileName, CHECKSUM, "mock://" + fileName, NOW, NOW);
    }

    private Long insertLeasedItem(
            Long batchId,
            Long productId,
            Long documentId,
            int ordinal
    ) {
        Long itemId = jdbcTemplate.queryForObject("""
                INSERT INTO document_batch_items (
                    batch_id, product_id, owner_id, product_document_id, ordinal,
                    source_file_name, source_media_type, source_file_size,
                    source_checksum, source_storage_key, due_at, attempt_count,
                    max_attempts, status, lease_owner, lease_until, lease_fence,
                    created_at, updated_at
                ) VALUES (
                    ?, ?, 1, ?, ?, ?, 'application/pdf', 1,
                    ?, ?, ?, 1, 1, 'LEASED', ?, ?, 1, ?, ?
                )
                RETURNING id
                """,
                Long.class,
                batchId,
                productId,
                documentId,
                ordinal,
                "item-" + ordinal + ".pdf",
                CHECKSUM,
                "mock://item-" + ordinal,
                NOW,
                WORKER,
                NOW.plusHours(1),
                NOW,
                NOW);
        jdbcTemplate.update("""
                INSERT INTO document_batch_item_attempts (
                    item_id, attempt_no, lease_fence, worker_owner, started_at
                ) VALUES (?, 1, 1, ?, ?)
                """, itemId, WORKER, NOW);
        return itemId;
    }
}
