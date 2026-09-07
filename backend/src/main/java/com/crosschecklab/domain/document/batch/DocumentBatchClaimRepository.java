package com.crosschecklab.domain.document.batch;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DocumentBatchClaimRepository extends Repository<DocumentBatchItem, Long> {

    Logger LOG = LoggerFactory.getLogger(DocumentBatchClaimRepository.class);

    @Query(value = "SELECT set_config('lock_timeout', :timeout, true)", nativeQuery = true)
    String setLocalLockTimeout(@Param("timeout") String timeout);

    @Query(value = "SELECT set_config('statement_timeout', :timeout, true)", nativeQuery = true)
    String setLocalStatementTimeout(@Param("timeout") String timeout);

    @Transactional
    @Query(value = """
            WITH claimable AS (
                SELECT i.id
                FROM document_batch_items i
                JOIN document_batches b ON b.id = i.batch_id
                WHERE b.cancel_requested_at IS NULL
                  AND i.cancel_requested_at IS NULL
                  AND i.attempt_count < i.max_attempts
                  AND :leaseUntil > :now
                  AND char_length(btrim(:workerOwner)) BETWEEN 1 AND 100
                  AND :claimLimit BETWEEN 1 AND 100
                  AND i.status IN ('PENDING', 'RETRY_WAIT')
                  AND i.due_at <= :now
                ORDER BY i.due_at, i.id
                FOR UPDATE OF i SKIP LOCKED
                LIMIT :claimLimit
            ), claimed AS (
                UPDATE document_batch_items i
                SET status = 'LEASED',
                    lease_owner = :workerOwner,
                    lease_until = :leaseUntil,
                    attempt_count = i.attempt_count + 1,
                    lease_fence = i.lease_fence + 1,
                    updated_at = :now
                FROM claimable c
                WHERE i.id = c.id
                RETURNING i.*
            ), audited AS (
                INSERT INTO document_batch_item_attempts (
                    item_id, attempt_no, lease_fence, worker_owner, started_at
                )
                SELECT c.id, c.attempt_count, c.lease_fence, c.lease_owner, :now
                FROM claimed c
                RETURNING item_id
            )
            SELECT c.*
            FROM claimed c
            JOIN audited a ON a.item_id = c.id
            ORDER BY c.due_at, c.id
            """, nativeQuery = true)
    List<DocumentBatchItem> claimDue(
            @Param("workerOwner") String workerOwner,
            @Param("now") OffsetDateTime now,
            @Param("leaseUntil") OffsetDateTime leaseUntil,
            @Param("claimLimit") int claimLimit
    );

    /**
     * Serializes lifecycle statements for sibling items in one batch. This must be called inside
     * the same transaction immediately before a lifecycle statement so that the latter receives a
     * fresh READ COMMITTED snapshot after any preceding sibling transition has committed.
     */
    @Query(value = """
            SELECT b.id
            FROM document_batches b
            JOIN document_batch_items i ON i.batch_id = b.id
            WHERE i.id = :itemId
              AND i.status = 'LEASED'
              AND i.lease_owner = :workerOwner
              AND i.lease_fence = :leaseFence
            FOR UPDATE OF b
            """, nativeQuery = true)
    Optional<Long> lockClaimBatch(
            @Param("itemId") Long itemId,
            @Param("workerOwner") String workerOwner,
            @Param("leaseFence") long leaseFence
    );

    /**
     * Starts extraction only while this exact lease is current. A cancellation already recorded on
     * the item is finalized instead, including its attempt audit, and no extraction target is returned.
     * An empty result can also mean that the lease is stale or that an expected dependent row was
     * absent; callers must run the fenced failure transition before abandoning the lifecycle.
     */
    @Transactional
    @Query(value = """
            WITH eligible AS (
                SELECT i.id, i.batch_id, i.product_document_id, i.source_file_name,
                       i.source_media_type, i.source_storage_key, i.attempt_count,
                       coalesce(i.cancel_requested_at, b.cancel_requested_at) AS cancel_requested_at,
                       coalesce(i.cancellation_reason, b.cancellation_reason) AS cancellation_reason
                FROM document_batch_items i
                JOIN document_batches b ON b.id = i.batch_id
                WHERE i.id = :itemId
                  AND i.status = 'LEASED'
                  AND i.lease_owner = :workerOwner
                  AND i.lease_fence = :leaseFence
                  AND i.lease_until > :startedAt
                FOR UPDATE
            ), transitioned AS (
                UPDATE document_batch_items i
                SET status = 'CANCELLED',
                    cancel_requested_at = e.cancel_requested_at,
                    cancellation_reason = e.cancellation_reason,
                    cancelled_at = greatest(:startedAt, e.cancel_requested_at),
                    terminal_at = greatest(:startedAt, e.cancel_requested_at),
                    lease_owner = NULL,
                    lease_until = NULL,
                    updated_at = greatest(:startedAt, e.cancel_requested_at)
                FROM eligible e
                WHERE i.id = e.id
                  AND e.cancel_requested_at IS NOT NULL
                RETURNING i.id, i.batch_id, i.attempt_count, i.status, i.terminal_at,
                          i.updated_at, i.cancel_requested_at, i.cancellation_reason,
                          i.quarantine_reason
            ), cancelled_audit AS (
                UPDATE document_batch_item_attempts a
                SET ended_at = c.terminal_at,
                    outcome = 'CANCELLED'
                FROM transitioned c
                WHERE a.item_id = c.id
                  AND a.attempt_no = c.attempt_count
                RETURNING a.item_id
            ), document_started AS (
                UPDATE product_documents d
                SET extract_status = 'EXTRACTING',
                    extraction_error_code = NULL,
                    extraction_error_message = NULL,
                    extraction_error_retryable = FALSE,
                    updated_at = :startedAt
                FROM eligible e
                WHERE d.id = e.product_document_id
                  AND e.cancel_requested_at IS NULL
                RETURNING d.id
            )
            """ + DocumentBatchRepository.REFRESH_AGGREGATE_CTE + """
            SELECT e.product_document_id AS "documentId",
                   e.source_file_name AS "fileName",
                   e.source_media_type AS "mediaType",
                   e.source_storage_key AS "storageKey"
            FROM eligible e
            JOIN document_started d ON d.id = e.product_document_id
            WHERE NOT EXISTS (SELECT 1 FROM cancelled_audit)
              AND NOT EXISTS (SELECT 1 FROM batch_updated)
            """, nativeQuery = true)
    Optional<ClaimedExtractionTarget> beginClaimedExtraction(
            @Param("itemId") Long itemId,
            @Param("workerOwner") String workerOwner,
            @Param("leaseFence") long leaseFence,
            @Param("startedAt") OffsetDateTime startedAt
    );

    /**
     * Atomically publishes both document text and item success behind the lease fence. A zero result
     * does not prove that the lease is stale, so callers must run the fenced failure transition.
     */
    @Transactional
    @Query(value = """
            WITH eligible AS (
                SELECT i.id, i.batch_id, i.product_document_id, i.attempt_count,
                       coalesce(i.cancel_requested_at, b.cancel_requested_at) AS cancel_requested_at,
                       coalesce(i.cancellation_reason, b.cancellation_reason) AS cancellation_reason
                FROM document_batch_items i
                JOIN document_batches b ON b.id = i.batch_id
                WHERE i.id = :itemId
                  AND i.status = 'LEASED'
                  AND i.lease_owner = :workerOwner
                  AND i.lease_fence = :leaseFence
                  AND i.lease_until > :finishedAt
                FOR UPDATE
            ), transitioned AS (
                UPDATE document_batch_items i
                SET status = CASE WHEN e.cancel_requested_at IS NULL
                                  THEN 'SUCCEEDED' ELSE 'CANCELLED' END,
                    terminal_at = CASE WHEN e.cancel_requested_at IS NULL
                        THEN :finishedAt ELSE greatest(:finishedAt, e.cancel_requested_at) END,
                    cancel_requested_at = CASE WHEN e.cancel_requested_at IS NULL
                        THEN i.cancel_requested_at ELSE e.cancel_requested_at END,
                    cancellation_reason = CASE WHEN e.cancel_requested_at IS NULL
                        THEN i.cancellation_reason ELSE e.cancellation_reason END,
                    cancelled_at = CASE WHEN e.cancel_requested_at IS NULL
                        THEN NULL ELSE greatest(:finishedAt, e.cancel_requested_at) END,
                    last_error_code = NULL,
                    last_error_message = NULL,
                    lease_owner = NULL,
                    lease_until = NULL,
                    updated_at = CASE WHEN e.cancel_requested_at IS NULL
                        THEN :finishedAt ELSE greatest(:finishedAt, e.cancel_requested_at) END
                FROM eligible e
                WHERE i.id = e.id
                RETURNING i.id, i.batch_id, i.product_document_id, i.attempt_count,
                          i.status, i.terminal_at, i.updated_at, i.cancel_requested_at,
                          i.cancellation_reason, i.quarantine_reason
            ), document_updated AS (
                UPDATE product_documents d
                SET extracted_text = :extractedText,
                    extract_status = 'READY',
                    extraction_error_code = NULL,
                    extraction_error_message = NULL,
                    extraction_error_retryable = FALSE,
                    confirmed = FALSE,
                    confirmed_by = NULL,
                    confirmed_at = NULL,
                    updated_at = :finishedAt
                FROM transitioned t
                WHERE d.id = t.product_document_id
                  AND t.status = 'SUCCEEDED'
                RETURNING d.id
            )
            """ + DocumentBatchRepository.REFRESH_AGGREGATE_CTE + """
            , attempt_updated AS (
                UPDATE document_batch_item_attempts a
                SET ended_at = t.terminal_at,
                    outcome = t.status
                FROM transitioned t
                LEFT JOIN document_updated d ON d.id = t.product_document_id
                WHERE a.item_id = t.id
                  AND a.attempt_no = t.attempt_count
                  AND EXISTS (SELECT 1 FROM batch_updated b WHERE b.id = t.batch_id)
                RETURNING a.item_id
            )
            SELECT (SELECT count(*) FROM transitioned) AS "itemAffectedRows",
                   (SELECT count(*) FROM batch_updated) AS "aggregateAffectedRows",
                   (SELECT count(*) FROM attempt_updated) AS "attemptAffectedRows",
                   (SELECT batch_id FROM transitioned LIMIT 1) AS "batchId",
                   (SELECT attempt_count FROM transitioned LIMIT 1) AS "attempt"
            """, nativeQuery = true)
    TransitionAffectedRows completeClaimAffectedRows(
            @Param("itemId") Long itemId,
            @Param("workerOwner") String workerOwner,
            @Param("leaseFence") long leaseFence,
            @Param("finishedAt") OffsetDateTime finishedAt,
            @Param("extractedText") String extractedText
    );

    default int completeClaim(
            Long itemId,
            String workerOwner,
            long leaseFence,
            OffsetDateTime finishedAt,
            String extractedText
    ) {
        TransitionAffectedRows result = completeClaimAffectedRows(
                itemId, workerOwner, leaseFence, finishedAt, extractedText);
        if (result.getItemAffectedRows() == 1
                && result.getAggregateAffectedRows() == 1
                && result.getAttemptAffectedRows() == 1) {
            LOG.info("Document batch complete transition affected rows. "
                            + "itemId={}, batchId={}, owner={}, fence={}, attempt={}, "
                            + "itemAffectedRows={}, attemptAffectedRows={}, aggregateAffectedRows={}",
                    itemId, result.getBatchId(), workerOwner, leaseFence, result.getAttempt(),
                    result.getItemAffectedRows(), result.getAttemptAffectedRows(),
                    result.getAggregateAffectedRows());
            return 1;
        }
        if (result.getItemAffectedRows() == 0
                && result.getAggregateAffectedRows() == 0
                && result.getAttemptAffectedRows() == 0) {
            return 0;
        }
        throw incompleteTransition(
                "complete", itemId, workerOwner, leaseFence, result);
    }

    /**
     * Records one failed attempt. Retryable failures wait with the supplied bounded backoff;
     * non-retryable and exhausted failures are quarantined. Cancellation has precedence. The exact
     * owner and fence may close an item that expired while its lifecycle was running, provided that
     * recovery has not already changed its status or fence. A zero result is therefore safe to treat
     * as stale after this statement has attempted the fenced transition: dependent document/audit
     * rows cannot prevent the item transition from executing.
     */
    @Transactional
    @Query(value = """
            WITH eligible AS (
                SELECT i.id, i.batch_id, i.product_document_id, i.attempt_count, i.max_attempts,
                       coalesce(i.cancel_requested_at, b.cancel_requested_at) AS cancel_requested_at,
                       coalesce(i.cancellation_reason, b.cancellation_reason) AS cancellation_reason
                FROM document_batch_items i
                JOIN document_batches b ON b.id = i.batch_id
                WHERE i.id = :itemId
                  AND i.status = 'LEASED'
                  AND i.lease_owner = :workerOwner
                  AND i.lease_fence = :leaseFence
                FOR UPDATE
            ), transitioned AS (
                UPDATE document_batch_items i
                SET status = CASE
                        WHEN e.cancel_requested_at IS NOT NULL THEN 'CANCELLED'
                        WHEN cast(:retryable as boolean) AND e.attempt_count < e.max_attempts
                        THEN 'RETRY_WAIT'
                        ELSE 'QUARANTINED'
                    END,
                    due_at = CASE
                        WHEN e.cancel_requested_at IS NULL
                         AND cast(:retryable as boolean) AND e.attempt_count < e.max_attempts
                        THEN cast(:nextDueAt as timestamptz) ELSE i.due_at END,
                    terminal_at = CASE
                        WHEN e.cancel_requested_at IS NOT NULL
                          OR NOT cast(:retryable as boolean)
                          OR e.attempt_count >= e.max_attempts
                        THEN CASE WHEN e.cancel_requested_at IS NULL
                            THEN cast(:finishedAt as timestamptz)
                            ELSE greatest(cast(:finishedAt as timestamptz),
                                          e.cancel_requested_at) END
                        ELSE cast(NULL as timestamptz) END,
                    cancel_requested_at = CASE WHEN e.cancel_requested_at IS NULL
                        THEN i.cancel_requested_at ELSE e.cancel_requested_at END,
                    cancellation_reason = CASE WHEN e.cancel_requested_at IS NULL
                        THEN i.cancellation_reason ELSE e.cancellation_reason END,
                    cancelled_at = CASE WHEN e.cancel_requested_at IS NOT NULL
                        THEN greatest(cast(:finishedAt as timestamptz), e.cancel_requested_at)
                        ELSE cast(NULL as timestamptz) END,
                    quarantined_at = CASE
                        WHEN e.cancel_requested_at IS NULL
                         AND (NOT cast(:retryable as boolean)
                              OR e.attempt_count >= e.max_attempts)
                        THEN cast(:finishedAt as timestamptz)
                        ELSE cast(NULL as timestamptz) END,
                    quarantine_reason = CASE
                        WHEN e.cancel_requested_at IS NULL
                         AND (NOT cast(:retryable as boolean)
                              OR e.attempt_count >= e.max_attempts)
                        THEN cast(:quarantineReason as varchar) ELSE NULL END,
                    last_error_code = CASE WHEN e.cancel_requested_at IS NULL
                                           THEN cast(:errorCode as varchar) ELSE NULL END,
                    last_error_message = CASE WHEN e.cancel_requested_at IS NULL
                                              THEN cast(:errorMessage as varchar) ELSE NULL END,
                    lease_owner = NULL,
                    lease_until = NULL,
                    updated_at = CASE WHEN e.cancel_requested_at IS NULL
                        THEN cast(:finishedAt as timestamptz)
                        ELSE greatest(cast(:finishedAt as timestamptz),
                                      e.cancel_requested_at) END
                FROM eligible e
                WHERE i.id = e.id
                  AND (cast(:nextDueAt as timestamptz) > cast(:finishedAt as timestamptz)
                       OR NOT cast(:retryable as boolean)
                       OR e.attempt_count >= e.max_attempts OR e.cancel_requested_at IS NOT NULL)
                RETURNING i.id, i.batch_id, i.product_document_id, i.attempt_count, i.status,
                          CASE WHEN i.status = 'RETRY_WAIT'
                               THEN cast(:finishedAt as timestamptz)
                               ELSE i.terminal_at END AS outcome_at,
                          i.terminal_at, i.updated_at, i.cancel_requested_at,
                          i.cancellation_reason, i.quarantine_reason
            ), document_updated AS (
                UPDATE product_documents d
                SET extract_status = 'FAILED',
                    extraction_error_code = cast(:errorCode as varchar),
                    extraction_error_message = cast(:errorMessage as varchar),
                    extraction_error_retryable = CASE WHEN t.status = 'RETRY_WAIT'
                                                      THEN TRUE ELSE FALSE END,
                    updated_at = cast(:finishedAt as timestamptz)
                FROM transitioned t
                WHERE d.id = t.product_document_id
                  AND t.status <> 'CANCELLED'
                RETURNING d.id
            )
            """ + DocumentBatchRepository.REFRESH_AGGREGATE_CTE + """
            , attempt_updated AS (
                UPDATE document_batch_item_attempts a
                SET ended_at = t.outcome_at,
                    outcome = t.status,
                    public_error_code = CASE WHEN t.status = 'CANCELLED' THEN NULL
                        ELSE cast(:errorCode as varchar) END,
                    public_error_message = CASE WHEN t.status = 'CANCELLED' THEN NULL
                        ELSE cast(:errorMessage as varchar) END,
                    diagnostic_reference = CASE WHEN t.status = 'CANCELLED' THEN NULL
                        ELSE cast(:diagnosticReference as varchar) END
                FROM transitioned t
                LEFT JOIN document_updated d ON d.id = t.product_document_id
                WHERE a.item_id = t.id
                  AND a.attempt_no = t.attempt_count
                  AND EXISTS (SELECT 1 FROM batch_updated b WHERE b.id = t.batch_id)
                RETURNING a.item_id
            )
            SELECT (SELECT count(*) FROM transitioned) AS "itemAffectedRows",
                   (SELECT count(*) FROM batch_updated) AS "aggregateAffectedRows",
                   (SELECT count(*) FROM attempt_updated) AS "attemptAffectedRows",
                   (SELECT batch_id FROM transitioned LIMIT 1) AS "batchId",
                   (SELECT attempt_count FROM transitioned LIMIT 1) AS "attempt"
            """, nativeQuery = true)
    TransitionAffectedRows failClaimAffectedRows(
            @Param("itemId") Long itemId,
            @Param("workerOwner") String workerOwner,
            @Param("leaseFence") long leaseFence,
            @Param("finishedAt") OffsetDateTime finishedAt,
            @Param("nextDueAt") OffsetDateTime nextDueAt,
            @Param("retryable") boolean retryable,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("quarantineReason") String quarantineReason,
            @Param("diagnosticReference") String diagnosticReference
    );

    default int failClaim(
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
        TransitionAffectedRows result = failClaimAffectedRows(
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
        if (result.getItemAffectedRows() == 1
                && result.getAggregateAffectedRows() == 1
                && result.getAttemptAffectedRows() == 1) {
            LOG.info("Document batch failure transition affected rows. "
                            + "itemId={}, batchId={}, owner={}, fence={}, attempt={}, "
                            + "itemAffectedRows={}, attemptAffectedRows={}, aggregateAffectedRows={}",
                    itemId, result.getBatchId(), workerOwner, leaseFence, result.getAttempt(),
                    result.getItemAffectedRows(), result.getAttemptAffectedRows(),
                    result.getAggregateAffectedRows());
            return 1;
        }
        if (result.getItemAffectedRows() == 0
                && result.getAggregateAffectedRows() == 0
                && result.getAttemptAffectedRows() == 0) {
            return 0;
        }
        throw incompleteTransition(
                "failure", itemId, workerOwner, leaseFence, result);
    }

    private static IllegalStateException incompleteTransition(
            String phase,
            Long itemId,
            String workerOwner,
            long leaseFence,
            TransitionAffectedRows result
    ) {
        return new IllegalStateException(
                "Document batch " + phase + " transition was incomplete. itemId=" + itemId
                        + ", batchId=" + result.getBatchId()
                        + ", owner=" + workerOwner
                        + ", fence=" + leaseFence
                        + ", attempt=" + result.getAttempt()
                        + ", itemAffectedRows=" + result.getItemAffectedRows()
                        + ", attemptAffectedRows=" + result.getAttemptAffectedRows()
                        + ", aggregateAffectedRows=" + result.getAggregateAffectedRows());
    }

    /** Reclaims only expired leases; pending work remains exclusively owned by normal claimers. */
    @Modifying
    @Transactional
    @Query(value = """
            WITH expired AS (
                SELECT i.id, i.batch_id, i.product_document_id, i.attempt_count, i.max_attempts,
                       coalesce(i.cancel_requested_at, b.cancel_requested_at) AS cancel_requested_at,
                       coalesce(i.cancellation_reason, b.cancellation_reason) AS cancellation_reason
                FROM document_batch_items i
                JOIN document_batches b ON b.id = i.batch_id
                WHERE i.status = 'LEASED'
                  AND i.lease_until <= cast(:now as timestamptz)
                ORDER BY i.lease_until, i.id
                FOR UPDATE SKIP LOCKED
                LIMIT :recoveryLimit
            ), transitioned AS (
                UPDATE document_batch_items i
                SET status = CASE
                        WHEN e.cancel_requested_at IS NOT NULL THEN 'CANCELLED'
                        WHEN e.attempt_count >= e.max_attempts THEN 'QUARANTINED'
                        ELSE 'RETRY_WAIT'
                    END,
                    due_at = CASE
                        WHEN e.cancel_requested_at IS NULL AND e.attempt_count < e.max_attempts
                        THEN cast(:now as timestamptz) + make_interval(secs => cast(least(
                            cast(:maxBackoffSeconds as numeric),
                            cast(:baseBackoffSeconds as numeric)
                                * power(cast(2 as numeric), greatest(e.attempt_count - 1, 0))
                        ) as integer))
                        ELSE i.due_at END,
                    terminal_at = CASE WHEN e.cancel_requested_at IS NOT NULL
                                            OR e.attempt_count >= e.max_attempts
                                       THEN CASE WHEN e.cancel_requested_at IS NULL
                                           THEN cast(:now as timestamptz)
                                           ELSE greatest(cast(:now as timestamptz), e.cancel_requested_at)
                                       END
                                       ELSE cast(NULL as timestamptz) END,
                    cancel_requested_at = CASE WHEN e.cancel_requested_at IS NULL
                        THEN i.cancel_requested_at ELSE e.cancel_requested_at END,
                    cancellation_reason = CASE WHEN e.cancel_requested_at IS NULL
                        THEN i.cancellation_reason ELSE e.cancellation_reason END,
                    cancelled_at = CASE WHEN e.cancel_requested_at IS NOT NULL
                        THEN greatest(cast(:now as timestamptz), e.cancel_requested_at)
                        ELSE cast(NULL as timestamptz) END,
                    quarantined_at = CASE WHEN e.cancel_requested_at IS NULL
                                               AND e.attempt_count >= e.max_attempts
                                          THEN cast(:now as timestamptz)
                                          ELSE cast(NULL as timestamptz) END,
                    quarantine_reason = CASE WHEN e.cancel_requested_at IS NULL
                                                  AND e.attempt_count >= e.max_attempts
                                             THEN 'Lease expired after the final extraction attempt.' ELSE NULL END,
                    last_error_code = CASE WHEN e.cancel_requested_at IS NULL
                        THEN 'DOCUMENT_EXTRACTION_LEASE_EXPIRED' ELSE NULL END,
                    last_error_message = CASE WHEN e.cancel_requested_at IS NULL
                        THEN 'Document extraction did not finish before its lease expired.' ELSE NULL END,
                    lease_owner = NULL,
                    lease_until = NULL,
                    updated_at = CASE WHEN e.cancel_requested_at IS NULL
                        THEN cast(:now as timestamptz)
                        ELSE greatest(cast(:now as timestamptz), e.cancel_requested_at) END
                FROM expired e
                WHERE i.id = e.id
                RETURNING i.id, i.batch_id, i.product_document_id, i.attempt_count, i.status,
                          CASE WHEN i.status = 'RETRY_WAIT' THEN cast(:now as timestamptz)
                               ELSE i.terminal_at END AS outcome_at,
                          i.terminal_at, i.updated_at, i.cancel_requested_at,
                          i.cancellation_reason, i.quarantine_reason
            ), document_updated AS (
                UPDATE product_documents d
                SET extract_status = 'FAILED',
                    extraction_error_code = 'DOCUMENT_EXTRACTION_LEASE_EXPIRED',
                    extraction_error_message = 'Document extraction did not finish before its lease expired.',
                    extraction_error_retryable = CASE WHEN t.status = 'RETRY_WAIT'
                                                      THEN TRUE ELSE FALSE END,
                    updated_at = cast(:now as timestamptz)
                FROM transitioned t
                WHERE d.id = t.product_document_id
                  AND t.status <> 'CANCELLED'
                RETURNING d.id
            )
            """ + DocumentBatchRepository.REFRESH_AGGREGATE_CTE + """
            UPDATE document_batch_item_attempts a
            SET ended_at = t.outcome_at,
                outcome = CASE t.status
                    WHEN 'RETRY_WAIT' THEN 'LEASE_EXPIRED_RETRY'
                    WHEN 'QUARANTINED' THEN 'LEASE_EXPIRED_QUARANTINED'
                    ELSE 'LEASE_EXPIRED_CANCELLED'
                END,
                public_error_code = CASE WHEN t.status = 'CANCELLED' THEN NULL
                                         ELSE 'DOCUMENT_EXTRACTION_LEASE_EXPIRED' END,
                public_error_message = CASE WHEN t.status = 'CANCELLED' THEN NULL
                                            ELSE 'Document extraction did not finish before its lease expired.' END
            FROM transitioned t
            LEFT JOIN document_updated d ON d.id = t.product_document_id
            WHERE a.item_id = t.id
              AND a.attempt_no = t.attempt_count
              AND EXISTS (SELECT 1 FROM batch_updated b WHERE b.id = t.batch_id)
            """, nativeQuery = true)
    int recoverExpiredLeases(
            @Param("now") OffsetDateTime now,
            @Param("recoveryLimit") int recoveryLimit,
            @Param("baseBackoffSeconds") int baseBackoffSeconds,
            @Param("maxBackoffSeconds") int maxBackoffSeconds
    );

    interface ClaimedExtractionTarget {
        Long getDocumentId();

        String getFileName();

        String getMediaType();

        String getStorageKey();
    }

    interface TransitionAffectedRows {
        int getItemAffectedRows();

        int getAggregateAffectedRows();

        int getAttemptAffectedRows();

        Long getBatchId();

        Integer getAttempt();
    }
}
