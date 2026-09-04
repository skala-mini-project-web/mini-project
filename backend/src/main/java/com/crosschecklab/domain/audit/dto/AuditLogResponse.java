package com.crosschecklab.domain.audit.dto;

import com.crosschecklab.domain.audit.AuditAction;
import com.crosschecklab.domain.audit.AuditEvent;
import com.crosschecklab.domain.audit.AuditResourceType;
import java.time.OffsetDateTime;

public record AuditLogResponse(
        Long auditId,
        OffsetDateTime createdAt,
        AuditAction action,
        AuditResourceType resourceType,
        Long resourceId,
        String resourceLabel,
        Long analysisId,
        Long actorId,
        String traceId
) {

    public static AuditLogResponse from(AuditEvent event) {
        return new AuditLogResponse(
                event.getAuditId(),
                event.getCreatedAt(),
                event.getAction(),
                event.getResourceType(),
                event.getResourceId(),
                event.getResourceLabel(),
                event.getAnalysisId(),
                event.getActorId(),
                event.getTraceId());
    }
}
