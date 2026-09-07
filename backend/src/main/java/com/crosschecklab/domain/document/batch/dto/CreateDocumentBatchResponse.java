package com.crosschecklab.domain.document.batch.dto;

import com.crosschecklab.domain.document.batch.DocumentBatchStatus;
import java.time.OffsetDateTime;

public record CreateDocumentBatchResponse(
        Long batchId,
        Long productId,
        String scenario,
        int acceptedItemCount,
        DocumentBatchStatus status,
        boolean idempotentReplay,
        OffsetDateTime acceptedAt,
        String statusUrl
) {

    public static CreateDocumentBatchResponse from(
            DocumentBatchResponse batch,
            boolean idempotentReplay
    ) {
        return new CreateDocumentBatchResponse(
                batch.batchId(),
                batch.productId(),
                batch.scenario(),
                batch.requestedItemCount(),
                batch.status(),
                idempotentReplay,
                batch.createdAt(),
                "/api/document-batches/" + batch.batchId());
    }
}
