package com.crosschecklab.domain.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.crosschecklab.domain.document.batch.DocumentBatchClaimRepository;
import com.crosschecklab.domain.document.batch.DocumentBatchItem;
import com.crosschecklab.support.IntegrationTestSupport;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class DocumentBatchConcurrencyIntegrationTest extends IntegrationTestSupport {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-07T02:00:00Z");
    private static final String CHECKSUM = "c".repeat(64);
    private static final String OWNER_USERNAME = "pm_park";

    private final List<Long> createdProductIds = new ArrayList<>();
    private final List<Future<?>> testFutures = new ArrayList<>();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DocumentBatchClaimRepository claims;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUpCreatedRows() throws InterruptedException {
        waitForTestFutures();
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
        testFutures.clear();
    }

    private void waitForTestFutures() throws InterruptedException {
        for (Future<?> future : testFutures) {
            try {
                future.get();
            } catch (ExecutionException ignored) {
                // The test assertion reports task failures; cleanup must still remove its fixture.
            }
        }
    }

    @Test
    void twoWorkersNeverClaimTheSameItem() throws Exception {
        Fixture fixture = insertPendingFixture("exclusive-claim", 2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<List<DocumentBatchItem>> first = workers.submit(
                    () -> claimAfterBarrier("worker-a", ready, start, NOW));
            Future<List<DocumentBatchItem>> second = workers.submit(
                    () -> claimAfterBarrier("worker-b", ready, start, NOW));
            testFutures.add(first);
            testFutures.add(second);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<DocumentBatchItem> firstClaims = first.get(5, TimeUnit.SECONDS);
            List<DocumentBatchItem> secondClaims = second.get(5, TimeUnit.SECONDS);
            assertThat(firstClaims.size() + secondClaims.size()).isEqualTo(1);
            assertThat(List.of(firstClaims, secondClaims).stream()
                    .flatMap(List::stream)
                    .map(DocumentBatchItem::getId)
                    .distinct())
                    .hasSize(1);
        }

        assertThat(jdbcTemplate.queryForObject("""
                SELECT attempt_count = 1
                       AND lease_fence = 1
                       AND status = 'LEASED'
                FROM document_batch_items
                WHERE id = ?
                """, Boolean.class, fixture.itemId())).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM document_batch_item_attempts WHERE item_id = ?
                """, Integer.class, fixture.itemId())).isEqualTo(1);
    }

    @Test
    void cancellationRequestWinsAgainstLateCompletion() throws Exception {
        Fixture fixture = insertPendingFixture("cancel-vs-complete", 2);
        DocumentBatchItem leased = claims.claimDue(
                "late-worker", NOW, NOW.plusMinutes(5), 1).getFirst();
        CountDownLatch cancellationCommitted = new CountDownLatch(1);

        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<Integer> cancellation = workers.submit(() -> {
                Integer updated = new TransactionTemplate(transactionManager).execute(status ->
                        jdbcTemplate.update("""
                                UPDATE document_batches
                                SET cancel_requested_at = ?, cancellation_reason = ?, updated_at = ?
                                WHERE id = ? AND status = 'PENDING'
                                """, NOW.plusMinutes(1), "Manager cancellation.",
                                NOW.plusMinutes(1), fixture.batchId()));
                cancellationCommitted.countDown();
                return updated;
            });
            Future<Integer> completion = workers.submit(() -> {
                assertThat(cancellationCommitted.await(5, TimeUnit.SECONDS)).isTrue();
                return claims.completeClaim(
                        leased.getId(), "late-worker", leased.getLeaseFence(),
                        NOW.plusMinutes(2), "must not be published");
            });
            testFutures.add(cancellation);
            testFutures.add(completion);

            assertThat(cancellation.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(completion.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }

        assertThat(jdbcTemplate.queryForObject("""
                SELECT i.status = 'CANCELLED'
                       AND i.cancellation_reason = 'Manager cancellation.'
                       AND b.status = 'CANCELLED'
                       AND b.cancellation_reason = 'Manager cancellation.'
                       AND d.extracted_text IS NULL
                       AND d.extract_status = 'UPLOADED'
                       AND a.outcome = 'CANCELLED'
                FROM document_batch_items i
                JOIN document_batches b ON b.id = i.batch_id
                JOIN product_documents d ON d.id = i.product_document_id
                JOIN document_batch_item_attempts a ON a.item_id = i.id
                WHERE i.id = ?
                """, Boolean.class, fixture.itemId())).isTrue();
    }

    @Test
    void concurrentRetryClaimCreatesExactlyOneNewAttempt() throws Exception {
        Fixture fixture = insertPendingFixture("concurrent-retry", 2);
        DocumentBatchItem initial = claims.claimDue(
                "initial-worker", NOW, NOW.plusMinutes(5), 1).getFirst();
        assertThat(claims.failClaim(
                initial.getId(), "initial-worker", initial.getLeaseFence(), NOW.plusMinutes(1),
                NOW.plusMinutes(2), true, "TEMPORARY_OCR_FAILURE", "Temporary OCR failure.",
                "Attempts exhausted.", "concurrent-retry-1")).isEqualTo(1);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<List<DocumentBatchItem>> first = workers.submit(
                    () -> claimAfterBarrier("retry-worker-a", ready, start, NOW.plusMinutes(2)));
            Future<List<DocumentBatchItem>> second = workers.submit(
                    () -> claimAfterBarrier("retry-worker-b", ready, start, NOW.plusMinutes(2)));
            testFutures.add(first);
            testFutures.add(second);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).size()
                    + second.get(5, TimeUnit.SECONDS).size()).isEqualTo(1);
        }

        assertThat(jdbcTemplate.queryForObject("""
                SELECT attempt_count = 2 AND lease_fence = 2 AND status = 'LEASED'
                FROM document_batch_items
                WHERE id = ?
                """, Boolean.class, fixture.itemId())).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) = 2
                       AND count(*) FILTER (WHERE attempt_no = 2 AND ended_at IS NULL) = 1
                FROM document_batch_item_attempts
                WHERE item_id = ?
                """, Boolean.class, fixture.itemId())).isTrue();
    }

    private List<DocumentBatchItem> claimAfterBarrier(
            String worker,
            CountDownLatch ready,
            CountDownLatch start,
            OffsetDateTime now
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("workers did not reach claim barrier");
        }
        return new TransactionTemplate(transactionManager).execute(status ->
                claims.claimDue(worker, now, now.plusMinutes(5), 1));
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
        return new Fixture(batchId, itemId);
    }

    private record Fixture(Long batchId, Long itemId) {
    }
}
