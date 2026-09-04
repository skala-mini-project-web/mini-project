package com.crosschecklab.domain.audit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends Repository<AuditEvent, Long> {

    AuditEvent save(AuditEvent auditEvent);

    @Query(value = """
            SELECT *
            FROM audit_events
            WHERE created_at < :snapshotCreatedAt
               OR (created_at = :snapshotCreatedAt AND audit_id <= :snapshotAuditId)
            ORDER BY created_at DESC, audit_id DESC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<AuditEvent> findOffsetPage(
            @Param("snapshotCreatedAt") OffsetDateTime snapshotCreatedAt,
            @Param("snapshotAuditId") long snapshotAuditId,
            @Param("offset") long offset,
            @Param("limit") int limit);

    Optional<AuditEvent> findFirstByOrderByCreatedAtDescAuditIdDesc();

    @Query(value = """
            SELECT COUNT(*)
            FROM audit_events
            WHERE created_at < :snapshotCreatedAt
               OR (created_at = :snapshotCreatedAt AND audit_id <= :snapshotAuditId)
            """, nativeQuery = true)
    long countAtOrBefore(
            @Param("snapshotCreatedAt") OffsetDateTime snapshotCreatedAt,
            @Param("snapshotAuditId") long snapshotAuditId);

    long count();
}
