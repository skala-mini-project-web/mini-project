package com.crosschecklab.domain.document.batch.dto;

import com.crosschecklab.domain.document.batch.DocumentBatchItem;
import com.crosschecklab.domain.document.batch.DocumentBatchItemAttempt;
import com.crosschecklab.domain.document.batch.DocumentBatchItemStatus;
import java.time.OffsetDateTime;

public record DocumentBatchItemResponse(
        Long itemId,
        Long batchId,
        Long documentId,
        int ordinal,
        String fileName,
        String mediaType,
        long fileSize,
        DocumentBatchItemStatus status,
        int attemptCount,
        int maxAttempts,
        String errorCode,
        String errorMessage,
        OffsetDateTime errorTimestamp,
        OffsetDateTime dueAt,
        OffsetDateTime terminalAt,
        String cancellationReason,
        String quarantineReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static DocumentBatchItemResponse from(
            DocumentBatchItem item,
            DocumentBatchItemAttempt latestAttempt
    ) {
        String errorCode = latestAttempt != null && latestAttempt.getPublicErrorCode() != null
                ? latestAttempt.getPublicErrorCode() : item.getLastErrorCode();
        String errorMessage = latestAttempt != null && latestAttempt.getPublicErrorMessage() != null
                ? latestAttempt.getPublicErrorMessage() : item.getLastErrorMessage();
        OffsetDateTime errorTimestamp = latestAttempt == null ? item.getTerminalAt()
                : latestAttempt.getEndedAt() != null
                        ? latestAttempt.getEndedAt() : latestAttempt.getStartedAt();
        return new DocumentBatchItemResponse(
                item.getId(),
                item.getBatchId(),
                item.getProductDocumentId(),
                item.getOrdinal(),
                item.getSourceFileName(),
                item.getSourceMediaType(),
                item.getSourceFileSize(),
                item.getStatus(),
                item.getAttemptCount(),
                item.getMaxAttempts(),
                errorCode,
                errorMessage,
                errorTimestamp,
                item.getDueAt(),
                item.getTerminalAt(),
                item.getCancellationReason(),
                item.getQuarantineReason(),
                item.getCreatedAt(),
                item.getUpdatedAt());
    }
}
