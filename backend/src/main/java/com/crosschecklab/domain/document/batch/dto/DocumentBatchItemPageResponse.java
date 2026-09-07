package com.crosschecklab.domain.document.batch.dto;

import java.util.List;

public record DocumentBatchItemPageResponse(
        Long batchId,
        List<DocumentBatchItemResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public DocumentBatchItemPageResponse {
        items = List.copyOf(items);
    }
}
