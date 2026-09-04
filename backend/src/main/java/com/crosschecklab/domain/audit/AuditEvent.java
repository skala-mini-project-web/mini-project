package com.crosschecklab.domain.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Getter
@Immutable
@Table(name = "audit_events")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditEvent {

    private static final int TRACE_ID_MAX_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_id", updatable = false)
    private Long auditId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "trace_id", nullable = false, updatable = false, length = 64)
    private String traceId;

    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 32)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, updatable = false, length = 20)
    private AuditResourceType resourceType;

    @Column(name = "resource_id", nullable = false, updatable = false)
    private Long resourceId;

    @Column(name = "resource_label", updatable = false, length = 255)
    private String resourceLabel;

    @Column(name = "analysis_id", updatable = false)
    private Long analysisId;

    public static AuditEvent create(String traceId, Long actorId, AuditAction action,
                                    AuditResourceType resourceType, Long resourceId,
                                    String resourceLabel, Long analysisId) {
        Objects.requireNonNull(traceId, "traceId");
        if (traceId.isBlank() || traceId.length() > TRACE_ID_MAX_LENGTH) {
            throw new IllegalArgumentException("traceId must be nonblank and at most 64 characters");
        }
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resourceType, "resourceType");
        if (action.getResourceType() != resourceType) {
            throw new IllegalArgumentException("action is incompatible with resourceType");
        }

        AuditEvent event = new AuditEvent();
        event.traceId = traceId;
        event.actorId = actorId;
        event.action = action;
        event.resourceType = resourceType;
        event.resourceId = Objects.requireNonNull(resourceId, "resourceId");
        event.resourceLabel = resourceLabel;
        event.analysisId = analysisId;
        return event;
    }
}
