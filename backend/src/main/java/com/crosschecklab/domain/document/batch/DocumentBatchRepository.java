package com.crosschecklab.domain.document.batch;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface DocumentBatchRepository extends Repository<DocumentBatch, Long> {

    /*
     * Appended to fenced item-transition statements. "transitioned" must expose the item columns
     * selected below. Overlaying its RETURNING rows is required because PostgreSQL data-modifying
     * CTEs share a snapshot and cannot otherwise observe their sibling UPDATE.
     */
    String REFRESH_AGGREGATE_CTE = """
            , aggregate_items AS (
                SELECT i.batch_id,
                       i.id,
                       coalesce(t.status, i.status) AS status,
                       coalesce(t.terminal_at, i.terminal_at) AS terminal_at,
                       coalesce(t.updated_at, i.updated_at) AS updated_at,
                       CASE WHEN t.id IS NULL THEN i.cancel_requested_at
                            ELSE t.cancel_requested_at END AS cancel_requested_at,
                       CASE WHEN t.id IS NULL THEN i.cancellation_reason
                            ELSE t.cancellation_reason END AS cancellation_reason,
                       CASE WHEN t.id IS NULL THEN i.quarantine_reason
                            ELSE t.quarantine_reason END AS quarantine_reason
                FROM document_batch_items i
                JOIN (SELECT DISTINCT batch_id FROM transitioned) changed
                  ON changed.batch_id = i.batch_id
                LEFT JOIN transitioned t ON t.id = i.id
            ), aggregate_state AS (
                SELECT batch_id,
                       CASE
                           WHEN count(*) FILTER (
                               WHERE status IN ('PENDING', 'LEASED', 'RETRY_WAIT')) > 0
                           THEN 'PENDING'
                           WHEN count(*) FILTER (WHERE status = 'QUARANTINED') > 0
                           THEN 'QUARANTINED'
                           WHEN count(*) FILTER (WHERE status = 'CANCELLED') > 0
                           THEN 'CANCELLED'
                           ELSE 'SUCCEEDED'
                       END AS status,
                       max(terminal_at) AS terminal_at,
                       max(updated_at) AS updated_at,
                       (array_agg(cancel_requested_at ORDER BY terminal_at DESC, id DESC)
                           FILTER (WHERE status = 'CANCELLED'))[1] AS cancel_requested_at,
                       (array_agg(cancellation_reason ORDER BY terminal_at DESC, id DESC)
                           FILTER (WHERE status = 'CANCELLED'))[1] AS cancellation_reason,
                       (array_agg(quarantine_reason ORDER BY terminal_at DESC, id DESC)
                           FILTER (WHERE status = 'QUARANTINED'))[1] AS quarantine_reason
                FROM aggregate_items
                GROUP BY batch_id
            ), batch_updated AS (
                UPDATE document_batches b
                SET status = s.status,
                    cancel_requested_at = CASE
                        WHEN s.status = 'PENDING' THEN b.cancel_requested_at
                        WHEN s.status = 'CANCELLED' THEN s.cancel_requested_at
                        ELSE NULL
                    END,
                    cancellation_reason = CASE
                        WHEN s.status = 'PENDING' THEN b.cancellation_reason
                        WHEN s.status = 'CANCELLED' THEN s.cancellation_reason
                        ELSE NULL
                    END,
                    cancelled_at = CASE WHEN s.status = 'CANCELLED'
                                        THEN cast(s.terminal_at as timestamptz)
                                        ELSE cast(NULL as timestamptz) END,
                    quarantine_reason = CASE WHEN s.status = 'QUARANTINED'
                                             THEN s.quarantine_reason ELSE NULL END,
                    quarantined_at = CASE WHEN s.status = 'QUARANTINED'
                                          THEN cast(s.terminal_at as timestamptz)
                                          ELSE cast(NULL as timestamptz) END,
                    terminal_at = CASE WHEN s.status = 'PENDING'
                                       THEN cast(NULL as timestamptz)
                                       ELSE cast(s.terminal_at as timestamptz) END,
                    updated_at = greatest(b.updated_at, s.updated_at)
                FROM aggregate_state s
                WHERE b.id = s.batch_id
                RETURNING b.id
            )
            """;

    DocumentBatch save(DocumentBatch batch);

    Optional<DocumentBatch> findByIdAndOwner_Id(Long id, Long ownerId);

    Optional<DocumentBatch> findByOwner_IdAndIdempotencyKey(Long ownerId, String idempotencyKey);

    @Query("select b.status from DocumentBatch b where b.id = :batchId")
    Optional<DocumentBatchStatus> findStatusById(@Param("batchId") Long batchId);

    Page<DocumentBatch> findAllByOwner_IdOrderByCreatedAtDesc(Long ownerId, Pageable pageable);

    Page<DocumentBatch> findAllByOwner_IdAndStatusOrderByCreatedAtDesc(
            Long ownerId,
            DocumentBatchStatus status,
            Pageable pageable
    );

    long countByOwner_IdAndStatus(Long ownerId, DocumentBatchStatus status);

    @Query("""
            select b.status as status, count(b) as count
            from DocumentBatch b
            where b.owner.id = :ownerId
            group by b.status
            """)
    List<StatusCount> countByStatusForOwner(@Param("ownerId") Long ownerId);

    interface StatusCount {
        DocumentBatchStatus getStatus();

        long getCount();
    }
}
