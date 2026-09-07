package com.crosschecklab.domain.document.batch.dto;

import com.crosschecklab.domain.document.batch.DocumentBatch;
import com.crosschecklab.domain.document.batch.DocumentBatchStatus;
import java.time.OffsetDateTime;

public record DocumentBatchResponse(
        Long batchId,
        Long productId,
        String scenario,
        int requestedItemCount,
        DocumentBatchStatus status,
        long pendingItemCount,
        long leasedItemCount,
        long retryWaitingItemCount,
        long succeededItemCount,
        long cancelledItemCount,
        long quarantinedItemCount,
        long totalAttemptCount,
        String cancellationReason,
        String quarantineReason,
        OffsetDateTime terminalAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static DocumentBatchResponse from(DocumentBatch batch) {
        return from(batch, batch.getStatus(), 0, 0, 0, 0, 0, 0, 0);
    }

    public static DocumentBatchResponse from(
            DocumentBatch batch,
            DocumentBatchStatus status,
            long pendingItemCount,
            long leasedItemCount,
            long retryWaitingItemCount,
            long succeededItemCount,
            long cancelledItemCount,
            long quarantinedItemCount,
            long totalAttemptCount
    ) {
        return from(
                batch, status, pendingItemCount, leasedItemCount, retryWaitingItemCount,
                succeededItemCount, cancelledItemCount, quarantinedItemCount, totalAttemptCount,
                batch.getCancellationReason(), batch.getQuarantineReason(), batch.getTerminalAt());
    }

    public static DocumentBatchResponse from(
            DocumentBatch batch,
            DocumentBatchStatus status,
            long pendingItemCount,
            long leasedItemCount,
            long retryWaitingItemCount,
            long succeededItemCount,
            long cancelledItemCount,
            long quarantinedItemCount,
            long totalAttemptCount,
            String cancellationReason,
            String quarantineReason,
            OffsetDateTime terminalAt
    ) {
        return new DocumentBatchResponse(
                batch.getId(),
                batch.getProductId(),
                batch.getScenario(),
                batch.getRequestedItemCount(),
                status,
                pendingItemCount,
                leasedItemCount,
                retryWaitingItemCount,
                succeededItemCount,
                cancelledItemCount,
                quarantinedItemCount,
                totalAttemptCount,
                cancellationReason,
                quarantineReason,
                terminalAt,
                batch.getCreatedAt(),
                batch.getUpdatedAt());
    }
}
