package com.crosschecklab.domain.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.crosschecklab.domain.document.batch.DocumentBatchClaimRepository;
import com.crosschecklab.domain.document.batch.DocumentBatchItem;
import com.crosschecklab.support.IntegrationTestSupport;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class DocumentBatchLifecycleIntegrationTest extends IntegrationTestSupport {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-07T01:00:00Z");
    private static final String CHECKSUM = "b".repeat(64);
    private static final String OWNER_USERNAME = "pm_park";

    private final List<Long> createdProductIds = new ArrayList<>();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DocumentBatchClaimRepository claims;

    @AfterEach
    void cleanUpCreatedRows() {
        jdbcTemplate.execute("TRUNCATE document_batches CASCADE");
        for (Long productId : createdProductIds) {
            jdbcTemplate.update("""
                    UPDATE product_documents
                    SET current_extraction_run_id = NULL
                    WHERE product_id = ?
                    """, productId);
            jdbcTemplate.update("""
                    DELETE FROM document_extraction_pages
                    WHERE extraction_run_id IN (
                        SELECT r.id
                        FROM document_extraction_runs r
                        JOIN product_documents d ON d.id = r.product_document_id
                        WHERE d.product_id = ?
                    )
                    """, productId);
            jdbcTemplate.update("""
                    DELETE FROM document_extraction_runs
                    WHERE product_document_id IN (
                        SELECT id FROM product_documents WHERE product_id = ?
                    )
                    """, productId);
            jdbcTemplate.update(
                    "DELETE FROM product_documents WHERE product_id = ?", productId);
            jdbcTemplate.update("DELETE FROM products WHERE id = ?", productId);
        }
        createdProductIds.clear();
    }

    @Test
    void retryableFailureIsReclaimedWithANewFenceThenSucceeds() {
        Fixture fixture = insertPendingFixture("retry-success", 2);
        DocumentBatchItem first = onlyClaim("first-worker", NOW);

        assertThat(claims.failClaim(
                first.getId(), "first-worker", first.getLeaseFence(), NOW.plusMinutes(1),
                NOW.plusMinutes(2), true, "TEMPORARY_OCR_FAILURE", "Temporary OCR failure.",
                "Attempts exhausted.", "retry-success-1")).isEqualTo(1);

        DocumentBatchItem second = onlyClaim("second-worker", NOW.plusMinutes(2));
        assertThat(second.getId()).isEqualTo(fixture.itemId());
        assertThat(second.getAttemptCount()).isEqualTo(2);
        assertThat(second.getLeaseFence()).isEqualTo(first.getLeaseFence() + 1);
        assertThat(claims.completeClaim(
                second.getId(), "second-worker", second.getLeaseFence(),
                NOW.plusMinutes(3), "recovered text")).isEqualTo(1);

        assertThat(jdbcTemplate.queryForMap("""
                SELECT i.status, i.attempt_count, i.lease_fence,
                       b.status AS batch_status, d.extract_status, d.extracted_text
                FROM document_batch_items i
                JOIN document_batches b ON b.id = i.batch_id
                JOIN product_documents d ON d.id = i.product_document_id
                WHERE i.id = ?
                """, fixture.itemId()))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("attempt_count", 2)
                .containsEntry("lease_fence", 2L)
                .containsEntry("batch_status", "SUCCEEDED")
                .containsEntry("extract_status", "READY")
                .containsEntry("extracted_text", "recovered text");
        assertThat(attemptOutcomes(fixture.itemId()))
                .containsExactly("RETRY_WAIT", "SUCCEEDED");
    }

    @Test
    void retryableFailureAtAttemptLimitIsQuarantined() {
        Fixture fixture = insertPendingFixture("retry-exhausted", 1);
        DocumentBatchItem claim = onlyClaim("exhaustion-worker", NOW);

        assertThat(claims.failClaim(
                claim.getId(), "exhaustion-worker", claim.getLeaseFence(), NOW.plusMinutes(1),
                NOW.plusMinutes(2), true, "TEMPORARY_OCR_FAILURE", "Temporary OCR failure.",
                "Retry budget exhausted.", "retry-exhausted-1")).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT i.status = 'QUARANTINED'
                       AND i.quarantine_reason = 'Retry budget exhausted.'
                       AND i.quarantined_at = ?
                       AND b.status = 'QUARANTINED'
                       AND b.terminal_at = i.terminal_at
                       AND a.outcome = 'QUARANTINED'
                FROM document_batch_items i
                JOIN document_batches b ON b.id = i.batch_id
                JOIN document_batch_item_attempts a ON a.item_id = i.id
                WHERE i.id = ?
                """, Boolean.class, NOW.plusMinutes(1), fixture.itemId())).isTrue();
        assertThat(claims.claimDue(
                "too-late-worker", NOW.plusDays(1), NOW.plusDays(1).plusMinutes(5), 1)).isEmpty();
    }

    @Test
    void expiredLeaseRecoveryAllowsReclaimAndIncrementsFence() {
        Fixture fixture = insertPendingFixture("expired-reclaim", 2);
        DocumentBatchItem expired = onlyClaim("expired-worker", NOW, NOW.plusSeconds(1));

        assertThat(claims.recoverExpiredLeases(NOW.plusSeconds(1), 10, 1, 30)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status = 'RETRY_WAIT'
                       AND lease_owner IS NULL
                       AND lease_until IS NULL
                       AND attempt_count = 1
                       AND lease_fence = 1
                FROM document_batch_items
                WHERE id = ?
                """, Boolean.class, fixture.itemId())).isTrue();

        DocumentBatchItem reclaimed = onlyClaim(
                "recovery-worker", NOW.plusSeconds(2), NOW.plusMinutes(5));
        assertThat(reclaimed.getId()).isEqualTo(expired.getId());
        assertThat(reclaimed.getAttemptCount()).isEqualTo(2);
        assertThat(reclaimed.getLeaseFence()).isEqualTo(2L);
        assertThat(attemptOutcomes(fixture.itemId()))
                .containsExactly("LEASE_EXPIRED_RETRY", (String) null);
    }

    private DocumentBatchItem onlyClaim(String worker, OffsetDateTime now) {
        return onlyClaim(worker, now, now.plusMinutes(5));
    }

    private DocumentBatchItem onlyClaim(
            String worker,
            OffsetDateTime now,
            OffsetDateTime leaseUntil
    ) {
        List<DocumentBatchItem> claimed = claims.claimDue(worker, now, leaseUntil, 1);
        assertThat(claimed).hasSize(1);
        return claimed.getFirst();
    }

    private List<String> attemptOutcomes(Long itemId) {
        return jdbcTemplate.queryForList("""
                SELECT outcome
                FROM document_batch_item_attempts
                WHERE item_id = ?
                ORDER BY attempt_no
                """, String.class, itemId);
    }

    private Fixture insertPendingFixture(String name, int maxAttempts) {
        Long ownerId = jdbcTemplate.queryForObject("""
                SELECT id FROM users WHERE username = ?
                """, Long.class, OWNER_USERNAME);
        Long productId = jdbcTemplate.queryForObject("""
                INSERT INTO products (owner_id, name, product_type, created_at, updated_at)
                VALUES (?, ?, 'INVESTMENT', ?, ?)
                RETURNING id
                """, Long.class, ownerId, name, NOW, NOW);
        createdProductIds.add(productId);
        Long documentId = jdbcTemplate.queryForObject("""
                INSERT INTO product_documents (
                    product_id, file_name, media_type, file_size, checksum,
                    storage_key, extract_status, confirmed, created_at, updated_at
                ) VALUES (?, ?, 'application/pdf', 1, ?, ?, 'UPLOADED', FALSE, ?, ?)
                RETURNING id
                """, Long.class, productId, name + ".pdf", CHECKSUM,
                "mock://" + name, NOW, NOW);
        Long batchId = jdbcTemplate.queryForObject("""
                INSERT INTO document_batches (
                    product_id, owner_id, scenario, requested_item_count,
                    idempotency_key, manifest_hash, status, created_at, updated_at
                ) VALUES (?, ?, 'REAL_EXTRACTION', 1, ?, ?, 'PENDING', ?, ?)
                RETURNING id
                """, Long.class, productId, ownerId, name + "-" + productId, CHECKSUM, NOW, NOW);
        Long itemId = jdbcTemplate.queryForObject("""
                INSERT INTO document_batch_items (
                    batch_id, product_id, owner_id, product_document_id, ordinal,
                    source_file_name, source_media_type, source_file_size,
                    source_checksum, source_storage_key, due_at, max_attempts, status,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, 1, ?, 'application/pdf', 1, ?, ?, ?, ?, 'PENDING', ?, ?)
                RETURNING id
                """, Long.class, batchId, productId, ownerId, documentId, name + ".pdf", CHECKSUM,
                "mock://" + name, NOW, maxAttempts, NOW, NOW);
        return new Fixture(itemId);
    }

    private record Fixture(Long itemId) {
    }
}
