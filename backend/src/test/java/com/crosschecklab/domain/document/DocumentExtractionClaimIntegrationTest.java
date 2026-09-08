package com.crosschecklab.domain.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crosschecklab.domain.document.DocumentExtractionTransitions.ExtractionClaim;
import com.crosschecklab.domain.document.extraction.DocumentExtractionResult;
import com.crosschecklab.domain.document.extraction.PageExtractionResult;
import com.crosschecklab.domain.document.extraction.TextExtractionService;
import com.crosschecklab.support.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class DocumentExtractionClaimIntegrationTest extends IntegrationTestSupport {

    private static final Duration PROCESSING_BUDGET = Duration.ofMinutes(5);
    private static final String CHECKSUM = "c".repeat(64);

    private final List<Long> createdProductIds = new ArrayList<>();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DocumentExtractionTransitions transitions;

    @Autowired
    private ProductDocumentRepository documents;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE document_batches, product_documents CASCADE");
        for (Long productId : createdProductIds) {
            jdbcTemplate.update("DELETE FROM products WHERE id = ?", productId);
        }
        createdProductIds.clear();
    }

    @Test
    void duplicateAdmissionTokenHasExactlyOneConcurrentWorkerClaim() throws Exception {
        Fixture fixture = insertStandalone("duplicate-request", "UPLOADED", Duration.ofMinutes(5));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService callers = Executors.newFixedThreadPool(2)) {
            Future<Optional<ExtractionClaim>> first = callers.submit(
                    () -> claimAfterBarrier(fixture, ready, start));
            Future<Optional<ExtractionClaim>> second = callers.submit(
                    () -> claimAfterBarrier(fixture, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ExtractionClaim> claims = List.of(first.get(), second.get()).stream()
                    .flatMap(Optional::stream)
                    .toList();
            assertThat(claims).hasSize(1);
            assertThat(claims.getFirst().workerToken()).isNotEqualTo(fixture.requestToken());
            assertThat(transitions.claimExtraction(
                    fixture.documentId(), fixture.requestToken(), PROCESSING_BUDGET)).isEmpty();
        }

        assertThat(jdbcTemplate.queryForObject("""
                SELECT extract_status = 'EXTRACTING'
                       AND extraction_token <> ?
                       AND extraction_lease_until > clock_timestamp()
                FROM product_documents WHERE id = ?
                """, Boolean.class, fixture.requestToken(), fixture.documentId())).isTrue();
    }

    @Test
    void expiredAdmissionIsRecoveredOnceAndCanBeManuallyReadmitted() {
        Fixture fixture = insertStandalone("expired-admission", "UPLOADED", Duration.ofSeconds(-1));

        assertThat(transitions.recoverExpiredExtraction(
                fixture.documentId(), fixture.requestToken())).isTrue();
        assertThat(transitions.recoverExpiredExtraction(
                fixture.documentId(), fixture.requestToken())).isFalse();
        assertThat(documentState(fixture.documentId()))
                .containsEntry("extract_status", "FAILED")
                .containsEntry("extraction_error_code", "DOCUMENT_EXTRACTION_TEMPORARY_FAILURE")
                .containsEntry("extraction_error_retryable", true)
                .containsEntry("extraction_token", fixture.requestToken());

        String secondRequest = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                UPDATE product_documents
                SET extract_status = 'EXTRACTING', extraction_token = ?,
                    extraction_lease_until = clock_timestamp() + interval '5 minutes',
                    extraction_error_code = NULL, extraction_error_message = NULL,
                    extraction_error_retryable = FALSE
                WHERE id = ?
                """, secondRequest, fixture.documentId());

        Optional<ExtractionClaim> retry = transitions.claimExtraction(
                fixture.documentId(), secondRequest, PROCESSING_BUDGET);
        assertThat(retry).isPresent();
        assertThat(retry.orElseThrow().workerToken()).isNotEqualTo(secondRequest);
    }

    @Test
    void staleWorkerCannotPublishTextFailureOrStructuredProvenanceAfterRetry() {
        Fixture fixture = insertStandalone("stale-worker", "UPLOADED", Duration.ofMinutes(5));
        ExtractionClaim first = transitions.claimExtraction(
                fixture.documentId(), fixture.requestToken(), PROCESSING_BUDGET).orElseThrow();
        jdbcTemplate.update("""
                UPDATE product_documents
                SET extraction_lease_until = clock_timestamp() - interval '1 second'
                WHERE id = ?
                """, fixture.documentId());
        assertThat(transitions.recoverExpiredExtraction(
                fixture.documentId(), first.workerToken())).isTrue();

        String secondRequest = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                UPDATE product_documents
                SET extract_status = 'EXTRACTING', extraction_token = ?,
                    extraction_lease_until = clock_timestamp() + interval '5 minutes',
                    extraction_error_code = NULL, extraction_error_message = NULL,
                    extraction_error_retryable = FALSE
                WHERE id = ?
                """, secondRequest, fixture.documentId());
        ExtractionClaim second = transitions.claimExtraction(
                fixture.documentId(), secondRequest, PROCESSING_BUDGET).orElseThrow();
        DocumentExtractionResult staleResult = structuredResult("stale structured text");

        assertThat(transitions.completeExtraction(
                fixture.documentId(), first.workerToken(), first.processingDeadline(),
                "stale plain text", true)).isFalse();
        assertThat(transitions.completeExtraction(
                fixture.documentId(), first.workerToken(), first.processingDeadline(),
                staleResult, false)).isFalse();
        assertThat(transitions.failExtraction(
                fixture.documentId(), first.workerToken(), "OLD_FAILURE", "old failure", false))
                .isFalse();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM document_extraction_runs WHERE product_document_id = ?
                """, Integer.class, fixture.documentId())).isZero();

        assertThat(transitions.completeExtraction(
                fixture.documentId(), second.workerToken(), second.processingDeadline(),
                structuredResult("fresh structured text"), false)).isTrue();
        assertThat(documentState(fixture.documentId()))
                .containsEntry("extract_status", "READY")
                .containsEntry("extracted_text", "fresh structured text");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM document_extraction_runs WHERE product_document_id = ?
                """, Integer.class, fixture.documentId())).isEqualTo(1);
    }

    @Test
    void batchMembershipExcludesClaimAndRecovery() {
        Fixture fixture = insertStandalone("batch-excluded", "UPLOADED", Duration.ofSeconds(-1));
        insertBatchMembership(fixture.documentId(), fixture.productId(), "batch-excluded");

        assertThat(transitions.claimExtraction(
                fixture.documentId(), fixture.requestToken(), PROCESSING_BUDGET)).isEmpty();
        assertThat(transitions.recoverExpiredExtraction(
                fixture.documentId(), fixture.requestToken())).isFalse();
        assertThat(documents.findExpiredExtractionCandidates(
                org.springframework.data.domain.PageRequest.of(0, 32)))
                .noneMatch(candidate -> candidate.getId().equals(fixture.documentId()));
        assertThat(documentState(fixture.documentId()))
                .containsEntry("extract_status", "UPLOADED")
                .containsEntry("extraction_token", fixture.requestToken());
    }

    @Test
    void expirationBoundaryIsRecoverableOnlyOnce() {
        Fixture fixture = insertStandalone("expiration-boundary", "EXTRACTING", Duration.ofMinutes(5));
        jdbcTemplate.update("""
                UPDATE product_documents SET extraction_lease_until = clock_timestamp() WHERE id = ?
                """, fixture.documentId());

        List<ProductDocumentRepository.ExpiredExtractionCandidate> candidates =
                documents.findExpiredExtractionCandidates(
                        org.springframework.data.domain.PageRequest.of(0, 32));
        assertThat(candidates)
                .anyMatch(candidate -> candidate.getId().equals(fixture.documentId())
                        && candidate.getExtractionToken().equals(fixture.requestToken()));
        assertThat(transitions.recoverExpiredExtraction(
                fixture.documentId(), fixture.requestToken())).isTrue();
        assertThat(transitions.recoverExpiredExtraction(
                fixture.documentId(), fixture.requestToken())).isFalse();
    }

    @Test
    void boundedTimeoutDiscardsInterruptionIgnoringLateResult() throws Exception {
        Fixture fixture = insertStandalone("bounded-timeout", "UPLOADED", Duration.ofMinutes(5));
        TextExtractionService extractor = mock(TextExtractionService.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(extractor.extractResult(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            started.countDown();
            while (release.getCount() > 0) {
                try {
                    release.await(20, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // Deliberately ignore cancellation to prove that the database fence is authoritative.
                }
            }
            return DocumentExtractionResult.withoutPageProvenance("late result", CHECKSUM);
        });
        ExecutorService extractionExecutor = Executors.newSingleThreadExecutor();
        DocumentExtractionRunner runner = new DocumentExtractionRunner(
                transitions, documents, extractor, extractionExecutor, Duration.ofMillis(100));

        try {
            runner.run(fixture.documentId(), fixture.requestToken());
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(documentState(fixture.documentId()))
                    .containsEntry("extract_status", "FAILED")
                    .containsEntry("extraction_error_code", "DOCUMENT_EXTRACTION_TEMPORARY_FAILURE")
                    .containsEntry("extraction_error_retryable", true);
            release.countDown();
            extractionExecutor.shutdown();
            assertThat(extractionExecutor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            assertThat(documentState(fixture.documentId()))
                    .containsEntry("extract_status", "FAILED")
                    .containsEntry("extracted_text", null);
        } finally {
            release.countDown();
            runner.shutdown();
        }
    }

    private Optional<ExtractionClaim> claimAfterBarrier(
            Fixture fixture,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("claim callers did not reach barrier");
        }
        return transitions.claimExtraction(
                fixture.documentId(), fixture.requestToken(), PROCESSING_BUDGET);
    }

    private Fixture insertStandalone(String name, String status, Duration leaseOffset) {
        Long ownerId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = 'pm_park'", Long.class);
        OffsetDateTime now = jdbcTemplate.queryForObject(
                "SELECT clock_timestamp()", OffsetDateTime.class);
        Long productId = jdbcTemplate.queryForObject("""
                INSERT INTO products (owner_id, name, product_type, created_at, updated_at)
                VALUES (?, ?, 'INVESTMENT', ?, ?)
                RETURNING id
                """, Long.class, ownerId, name, now, now);
        createdProductIds.add(productId);
        String requestToken = UUID.randomUUID().toString();
        Long documentId = jdbcTemplate.queryForObject("""
                INSERT INTO product_documents (
                    product_id, file_name, media_type, file_size, checksum, storage_key,
                    extract_status, extraction_token, extraction_lease_until,
                    confirmed, created_at, updated_at
                ) VALUES (?, ?, 'application/pdf', 1, ?, ?, ?, ?, ?, FALSE, ?, ?)
                RETURNING id
                """, Long.class, productId, name + ".pdf", CHECKSUM, "mock://" + name,
                status, requestToken, now.plus(leaseOffset), now, now);
        return new Fixture(productId, documentId, requestToken);
    }

    private void insertBatchMembership(Long documentId, Long productId, String name) {
        Long ownerId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM products WHERE id = ?", Long.class, productId);
        OffsetDateTime now = jdbcTemplate.queryForObject(
                "SELECT clock_timestamp()", OffsetDateTime.class);
        Long batchId = jdbcTemplate.queryForObject("""
                INSERT INTO document_batches (
                    product_id, owner_id, scenario, requested_item_count,
                    idempotency_key, manifest_hash, status, created_at, updated_at
                ) VALUES (?, ?, 'REAL_EXTRACTION', 1, ?, ?, 'PENDING', ?, ?)
                RETURNING id
                """, Long.class, productId, ownerId, name + "-" + productId, CHECKSUM, now, now);
        jdbcTemplate.update("""
                INSERT INTO document_batch_items (
                    batch_id, product_id, owner_id, product_document_id, ordinal,
                    source_file_name, source_media_type, source_file_size,
                    source_checksum, source_storage_key, due_at, max_attempts, status,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, 1, ?, 'application/pdf', 1, ?, ?, ?, 2, 'PENDING', ?, ?)
                """, batchId, productId, ownerId, documentId, name + ".pdf", CHECKSUM,
                "mock://" + name, now, now, now);
    }

    private java.util.Map<String, Object> documentState(Long documentId) {
        return jdbcTemplate.queryForMap("""
                SELECT extract_status, extracted_text, extraction_error_code,
                       extraction_error_retryable, extraction_token, extraction_lease_until
                FROM product_documents WHERE id = ?
                """, documentId);
    }

    private static DocumentExtractionResult structuredResult(String text) {
        String hash = sha256(text);
        return DocumentExtractionResult.ofPages(
                CHECKSUM, List.of(PageExtractionResult.pdfBox(1, text, hash)));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Fixture(Long productId, Long documentId, String requestToken) {
    }
}
