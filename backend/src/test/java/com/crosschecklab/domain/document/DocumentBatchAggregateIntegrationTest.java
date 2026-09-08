package com.crosschecklab.domain.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.crosschecklab.domain.document.batch.DocumentBatchClaimRepository;
import com.crosschecklab.domain.document.batch.DocumentBatchRepository;
import com.crosschecklab.domain.document.batch.DocumentBatchService;
import com.crosschecklab.domain.document.batch.DocumentBatchStatus;
import com.crosschecklab.domain.document.extraction.DocumentExtractionResult;
import com.crosschecklab.domain.document.extraction.PageExtractionResult;
import com.crosschecklab.global.common.enums.UserRole;
import com.crosschecklab.global.security.DemoUser;
import com.crosschecklab.support.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

class DocumentBatchAggregateIntegrationTest extends IntegrationTestSupport {

    private static final OffsetDateTime NOW = OffsetDateTime.now().plusYears(1);
    private static final String CHECKSUM = "a".repeat(64);
    private static final String WORKER = "aggregate-regression-worker";

    private final List<Long> createdProductIds = new ArrayList<>();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DocumentBatchClaimRepository claims;

    @Autowired
    private DocumentBatchRepository batches;

    @Autowired
    private DocumentBatchService batchService;

    @Autowired
    private DocumentExtractionTransitions transitions;

    @AfterEach
    void cleanUpCreatedRows() {
        if (TestTransaction.isActive()) {
            jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");
        }
        jdbcTemplate.execute("TRUNCATE document_batches, product_documents CASCADE");
        for (Long productId : createdProductIds) {
            jdbcTemplate.update("DELETE FROM products WHERE id = ?", productId);
        }
        createdProductIds.clear();
    }

    @Test
    @Transactional
    void successAndQuarantineItemsDurablyQuarantineTheirBatch() {
        Long productId = insertProduct("aggregate regression");
        Long succeededDocumentId = insertDocument(productId, "succeeded.pdf");
        Long quarantinedDocumentId = insertDocument(productId, "quarantined.pdf");
        Long batchId = insertBatch(productId, 2, "aggregate-regression-");
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
    void oneSucceededItemMakesItsBatchDurablyTerminal() {
        Long productId = insertProduct("single item aggregation");
        Long documentId = insertDocument(productId, "single.pdf");
        Long batchId = insertBatch(productId, 1, "single-item-");
        Long itemId = insertLeasedItem(batchId, productId, documentId, 1);

        assertThat(claims.completeClaim(
                itemId, WORKER, 1L, NOW.plusMinutes(1), "single item text")).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT b.status = 'SUCCEEDED'
                       AND b.terminal_at = ?
                       AND b.cancelled_at IS NULL
                       AND b.quarantined_at IS NULL
                       AND i.status = 'SUCCEEDED'
                       AND i.terminal_at = b.terminal_at
                       AND a.outcome = 'SUCCEEDED'
                       AND a.ended_at = b.terminal_at
                FROM document_batches b
                JOIN document_batch_items i ON i.batch_id = b.id
                JOIN document_batch_item_attempts a ON a.item_id = i.id
                WHERE b.id = ?
                """, Boolean.class, NOW.plusMinutes(1), batchId)).isTrue();
    }

    @Test
    @Transactional
    void explicitlyQuarantinedItemCannotBeClaimedAgain() {
        Long productId = insertProduct("quarantine claim guard");
        Long documentId = insertDocument(productId, "quarantine.pdf");
        Long batchId = insertBatch(productId, 1, "quarantine-guard-");
        Long itemId = insertPendingItem(batchId, productId, documentId, 1, 3);

        batchService.quarantineItem(
                batchId,
                itemId,
                "Explicitly quarantined.",
                new DemoUser(1L, "pm_park", "Product manager", UserRole.PRODUCT_MANAGER));

        assertThat(claims.claimDue(
                "future-worker", NOW.plusDays(1), NOW.plusDays(1).plusMinutes(5), 10)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status = 'QUARANTINED'
                       AND lease_owner IS NULL
                       AND lease_until IS NULL
                       AND attempt_count = 0
                       AND lease_fence = 0
                FROM document_batch_items
                WHERE id = ?
                """, Boolean.class, itemId)).isTrue();
    }

    @Test
    @Transactional
    void expiredButStillFencedClaimIsDurablyQuarantined() {
        Long productId = insertProduct("expired fence regression");
        Long documentId = insertDocument(productId, "expired.pdf");
        Long batchId = insertBatch(productId, 1, "expired-fence-");
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

    @Test
    void structuredBatchPdfCompletionPersistsCurrentRunAndPage() {
        Long productId = insertProduct("structured batch result");
        Long documentId = insertDocument(productId, "structured.pdf");
        Long batchId = insertBatch(productId, 1, "structured-batch-");
        Long itemId = insertLeasedItem(batchId, productId, documentId, 1);
        jdbcTemplate.update("""
                UPDATE product_documents
                SET extract_status = 'EXTRACTING'
                WHERE id = ?
                """, documentId);
        DocumentExtractionResult result = structuredResult("batch page text");

        assertThat(transitions.completeBatchExtraction(
                itemId, WORKER, 1L, NOW.plusMinutes(1), result)).isTrue();

        Map<String, Object> persisted = jdbcTemplate.queryForMap("""
                SELECT d.extract_status,
                       d.current_extraction_run_id,
                       d.extracted_text,
                       i.status AS item_status,
                       a.outcome AS attempt_outcome,
                       r.state AS run_state,
                       r.page_count,
                       p.page_number,
                       p.selected_method,
                       p.selected_text
                FROM product_documents d
                JOIN document_batch_items i ON i.product_document_id = d.id
                JOIN document_batch_item_attempts a
                  ON a.item_id = i.id AND a.attempt_no = i.attempt_count
                JOIN document_extraction_runs r ON r.id = d.current_extraction_run_id
                JOIN document_extraction_pages p ON p.extraction_run_id = r.id
                WHERE d.id = ?
                """, documentId);
        assertThat(persisted)
                .containsEntry("extract_status", "READY")
                .containsEntry("extracted_text", "batch page text")
                .containsEntry("item_status", "SUCCEEDED")
                .containsEntry("attempt_outcome", "SUCCEEDED")
                .containsEntry("run_state", "SUCCEEDED")
                .containsEntry("page_count", 1)
                .containsEntry("page_number", 1)
                .containsEntry("selected_method", "PDFBOX_TEXT")
                .containsEntry("selected_text", "batch page text");
        assertThat(persisted.get("current_extraction_run_id")).isNotNull();
    }

    @Test
    void staleFenceNeverPersistsStructuredBatchResult() {
        Long productId = insertProduct("stale structured batch result");
        Long documentId = insertDocument(productId, "stale-structured.pdf");
        Long batchId = insertBatch(productId, 1, "stale-structured-batch-");
        Long itemId = insertLeasedItem(batchId, productId, documentId, 1);
        jdbcTemplate.update("""
                UPDATE product_documents
                SET extract_status = 'EXTRACTING'
                WHERE id = ?
                """, documentId);

        assertThat(transitions.completeBatchExtraction(
                itemId, WORKER, 2L, NOW.plusMinutes(1), structuredResult("stale page text")))
                .isFalse();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT current_extraction_run_id IS NULL
                       AND extracted_text IS NULL
                       AND extract_status = 'EXTRACTING'
                FROM product_documents
                WHERE id = ?
                """, Boolean.class, documentId)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM document_extraction_runs
                WHERE product_document_id = ?
                """, Integer.class, documentId)).isZero();
    }

    private Long insertProduct(String name) {
        Long productId = jdbcTemplate.queryForObject("""
                INSERT INTO products (
                    owner_id, name, product_type, created_at, updated_at
                ) VALUES (1, ?, 'INVESTMENT', ?, ?)
                RETURNING id
                """, Long.class, name, NOW, NOW);
        createdProductIds.add(productId);
        return productId;
    }

    private Long insertBatch(Long productId, int requestedItemCount, String keyPrefix) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO document_batches (
                    product_id, owner_id, scenario, requested_item_count,
                    idempotency_key, manifest_hash, created_at, updated_at
                ) VALUES (?, 1, 'REAL_EXTRACTION', ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, productId, requestedItemCount, keyPrefix + productId, CHECKSUM, NOW, NOW);
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

    private Long insertPendingItem(
            Long batchId,
            Long productId,
            Long documentId,
            int ordinal,
            int maxAttempts
    ) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO document_batch_items (
                    batch_id, product_id, owner_id, product_document_id, ordinal,
                    source_file_name, source_media_type, source_file_size,
                    source_checksum, source_storage_key, due_at, max_attempts,
                    created_at, updated_at
                ) VALUES (
                    ?, ?, 1, ?, ?, ?, 'application/pdf', 1,
                    ?, ?, ?, ?, ?, ?
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
                maxAttempts,
                NOW,
                NOW);
    }

    private static DocumentExtractionResult structuredResult(String text) {
        String textHash = sha256(text);
        return DocumentExtractionResult.ofPages(
                CHECKSUM,
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
