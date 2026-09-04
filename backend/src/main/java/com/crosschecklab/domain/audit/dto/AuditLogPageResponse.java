package com.crosschecklab.domain.audit.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record AuditLogPageResponse(
        List<AuditLogResponse> items,
        long offset,
        int limit,
        long totalElements,
        OffsetDateTime snapshotCreatedAt,
        Long snapshotAuditId
) {

    public AuditLogPageResponse {
        items = List.copyOf(items);
    }
}
