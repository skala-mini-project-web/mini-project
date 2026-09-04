package com.crosschecklab.domain.audit;

import com.crosschecklab.domain.audit.dto.AuditLogPageResponse;
import com.crosschecklab.domain.audit.dto.AuditLogResponse;
import com.crosschecklab.global.common.enums.UserRole;
import com.crosschecklab.global.security.DemoUser;
import com.crosschecklab.global.security.OwnershipChecker;
import com.crosschecklab.global.trace.TraceIdFilter;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository auditEventRepository;
    private final OwnershipChecker ownershipChecker;
    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(DemoUser currentUser, AuditAction action, Long resourceId,
                       String resourceLabel, Long analysisId) {
        String traceId = Objects.requireNonNull(
                TraceIdFilter.currentTraceId(), "audit writes require a request trace");
        Long actorId = Objects.requireNonNull(
                Objects.requireNonNull(currentUser, "currentUser").id(),
                "audit writes require an authenticated actor");
        auditEventRepository.save(AuditEvent.create(
                traceId,
                actorId,
                action,
                action.getResourceType(),
                resourceId,
                resourceLabel,
                analysisId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void appendWithTrace(String traceId, Long actorId, AuditAction action, Long resourceId,
                                String resourceLabel, Long analysisId) {
        auditEventRepository.save(AuditEvent.create(
                Objects.requireNonNull(traceId, "audit writes require a server trace"),
                actorId,
                action,
                action.getResourceType(),
                resourceId,
                resourceLabel,
                analysisId));
        entityManager.flush();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AuditLogPageResponse list(long offset, int limit, OffsetDateTime snapshotCreatedAt,
                                     Long snapshotAuditId, DemoUser currentUser) {
        ownershipChecker.requireRole(currentUser, UserRole.COMPLIANCE_REVIEWER);

        SnapshotBoundary snapshot = snapshotCreatedAt == null
                ? auditEventRepository.findFirstByOrderByCreatedAtDescAuditIdDesc()
                        .map(event -> new SnapshotBoundary(
                                event.getCreatedAt(), event.getAuditId()))
                        .orElse(null)
                : new SnapshotBoundary(snapshotCreatedAt, snapshotAuditId);
        if (snapshot == null) {
            return new AuditLogPageResponse(
                    List.of(), offset, limit, 0, null, null);
        }

        List<AuditLogResponse> items = auditEventRepository.findOffsetPage(
                        snapshot.createdAt(), snapshot.auditId(), offset, limit).stream()
                .map(AuditLogResponse::from)
                .toList();
        long totalElements = auditEventRepository.countAtOrBefore(
                snapshot.createdAt(), snapshot.auditId());
        return new AuditLogPageResponse(
                items, offset, limit, totalElements, snapshot.createdAt(), snapshot.auditId());
    }

    private record SnapshotBoundary(OffsetDateTime createdAt, long auditId) {
    }
}
