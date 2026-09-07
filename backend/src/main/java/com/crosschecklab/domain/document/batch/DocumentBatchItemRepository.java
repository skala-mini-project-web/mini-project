package com.crosschecklab.domain.document.batch;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DocumentBatchItemRepository extends Repository<DocumentBatchItem, Long> {

    DocumentBatchItem save(DocumentBatchItem item);

    List<DocumentBatchItem> saveAll(Iterable<DocumentBatchItem> items);

    Optional<DocumentBatchItem> findByIdAndOwner_Id(Long id, Long ownerId);

    Page<DocumentBatchItem> findAllByBatch_IdAndOwner_IdOrderByOrdinalAsc(
            Long batchId,
            Long ownerId,
            Pageable pageable
    );

    long countByBatch_Id(Long batchId);

    long countByBatch_IdAndStatus(Long batchId, DocumentBatchItemStatus status);

    @Query("""
            select i.status as status, count(i) as count
            from DocumentBatchItem i
            where i.batch.id = :batchId
            group by i.status
            """)
    List<StatusCount> countByStatusForBatch(@Param("batchId") Long batchId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update DocumentBatchItem i
            set i.status = com.crosschecklab.domain.document.batch.DocumentBatchItemStatus.SUCCEEDED,
                i.terminalAt = :finishedAt,
                i.lastErrorCode = null,
                i.lastErrorMessage = null,
                i.leaseOwner = null,
                i.leaseUntil = null,
                i.updatedAt = :finishedAt
            where i.id = :itemId
              and i.status = com.crosschecklab.domain.document.batch.DocumentBatchItemStatus.LEASED
              and i.leaseOwner = :workerOwner
              and i.leaseFence = :leaseFence
              and i.leaseUntil > :finishedAt
              and i.cancelRequestedAt is null
            """)
    int markSucceeded(
            @Param("itemId") Long itemId,
            @Param("workerOwner") String workerOwner,
            @Param("leaseFence") long leaseFence,
            @Param("finishedAt") OffsetDateTime finishedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update DocumentBatchItem i
            set i.status = com.crosschecklab.domain.document.batch.DocumentBatchItemStatus.RETRY_WAIT,
                i.dueAt = :nextDueAt,
                i.lastErrorCode = :errorCode,
                i.lastErrorMessage = :errorMessage,
                i.leaseOwner = null,
                i.leaseUntil = null,
                i.updatedAt = :finishedAt
            where i.id = :itemId
              and i.status = com.crosschecklab.domain.document.batch.DocumentBatchItemStatus.LEASED
              and i.leaseOwner = :workerOwner
              and i.leaseFence = :leaseFence
              and i.leaseUntil > :finishedAt
              and i.attemptCount < i.maxAttempts
              and i.cancelRequestedAt is null
              and :nextDueAt > :finishedAt
              and length(:errorCode) between 1 and 60
              and length(:errorMessage) between 1 and 1000
              and trim(:errorCode) <> ''
              and trim(:errorMessage) <> ''
            """)
    int scheduleRetry(
            @Param("itemId") Long itemId,
            @Param("workerOwner") String workerOwner,
            @Param("leaseFence") long leaseFence,
            @Param("finishedAt") OffsetDateTime finishedAt,
            @Param("nextDueAt") OffsetDateTime nextDueAt,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update DocumentBatchItem i
            set i.status = com.crosschecklab.domain.document.batch.DocumentBatchItemStatus.QUARANTINED,
                i.terminalAt = :finishedAt,
                i.quarantinedAt = :finishedAt,
                i.quarantineReason = :reason,
                i.lastErrorCode = :errorCode,
                i.lastErrorMessage = :errorMessage,
                i.leaseOwner = null,
                i.leaseUntil = null,
                i.updatedAt = :finishedAt
            where i.id = :itemId
              and i.status = com.crosschecklab.domain.document.batch.DocumentBatchItemStatus.LEASED
              and i.leaseOwner = :workerOwner
              and i.leaseFence = :leaseFence
              and i.leaseUntil > :finishedAt
              and i.cancelRequestedAt is null
              and length(:errorCode) between 1 and 60
              and length(:errorMessage) between 1 and 1000
              and length(:reason) between 1 and 500
              and trim(:errorCode) <> ''
              and trim(:errorMessage) <> ''
              and trim(:reason) <> ''
            """)
    int markQuarantined(
            @Param("itemId") Long itemId,
            @Param("workerOwner") String workerOwner,
            @Param("leaseFence") long leaseFence,
            @Param("finishedAt") OffsetDateTime finishedAt,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("reason") String reason
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update DocumentBatchItem i
            set i.status = com.crosschecklab.domain.document.batch.DocumentBatchItemStatus.CANCELLED,
                i.cancelRequestedAt = coalesce(i.cancelRequestedAt, :finishedAt),
                i.cancellationReason = :reason,
                i.cancelledAt = :finishedAt,
                i.terminalAt = :finishedAt,
                i.leaseOwner = null,
                i.leaseUntil = null,
                i.updatedAt = :finishedAt
            where i.id = :itemId
              and i.status = com.crosschecklab.domain.document.batch.DocumentBatchItemStatus.LEASED
              and i.leaseOwner = :workerOwner
              and i.leaseFence = :leaseFence
              and i.leaseUntil > :finishedAt
              and (i.cancelRequestedAt is null or i.cancelRequestedAt <= :finishedAt)
              and length(:reason) between 1 and 500
              and trim(:reason) <> ''
            """)
    int markCancelled(
            @Param("itemId") Long itemId,
            @Param("workerOwner") String workerOwner,
            @Param("leaseFence") long leaseFence,
            @Param("finishedAt") OffsetDateTime finishedAt,
            @Param("reason") String reason
    );

    interface StatusCount {
        DocumentBatchItemStatus getStatus();

        long getCount();
    }
}
