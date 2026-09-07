package com.crosschecklab.domain.document.batch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(
        name = "document_batch_item_attempts",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_document_batch_item_attempts_item_attempt",
                columnNames = {"item_id", "attempt_no"})
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentBatchItemAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false, updatable = false)
    private DocumentBatchItem item;

    @Column(name = "attempt_no", nullable = false, updatable = false)
    private int attemptNo;

    @Column(name = "lease_fence", nullable = false, updatable = false)
    private long leaseFence;

    @Column(name = "worker_owner", nullable = false, updatable = false, length = 100)
    private String workerOwner;

    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(length = 30)
    private String outcome;

    @Column(name = "public_error_code", length = 60)
    private String publicErrorCode;

    @Column(name = "public_error_message", length = 1000)
    private String publicErrorMessage;

    @Column(name = "diagnostic_reference", length = 500)
    private String diagnosticReference;

    public Long getItemId() {
        return item.getId();
    }

    public boolean isFinished() {
        return endedAt != null;
    }
}
