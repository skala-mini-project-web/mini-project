package com.crosschecklab.domain.document.batch;

import com.crosschecklab.domain.product.Product;
import com.crosschecklab.domain.user.User;
import com.crosschecklab.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(
        name = "document_batches",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_document_batches_owner_idempotency_key",
                columnNames = {"owner_id", "idempotency_key"})
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentBatch extends BaseTimeEntity {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    private User owner;

    @Column(nullable = false, updatable = false, length = 60)
    private String scenario;

    @Column(name = "requested_item_count", nullable = false, updatable = false)
    private int requestedItemCount;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "manifest_hash", nullable = false, updatable = false, length = 64)
    private String manifestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentBatchStatus status;

    @Column(name = "cancel_requested_at")
    private OffsetDateTime cancelRequestedAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "quarantine_reason", length = 500)
    private String quarantineReason;

    @Column(name = "quarantined_at")
    private OffsetDateTime quarantinedAt;

    @Column(name = "terminal_at")
    private OffsetDateTime terminalAt;

    public static DocumentBatch create(
            Product product,
            User owner,
            String scenario,
            int requestedItemCount,
            String idempotencyKey,
            String manifestHash
    ) {
        requireNonNull(product, "product");
        requireNonNull(owner, "owner");
        requirePersistedId(product.getId(), "product");
        requirePersistedId(owner.getId(), "owner");
        if (!Objects.equals(product.getOwnerId(), owner.getId())) {
            throw new IllegalArgumentException("owner must own product");
        }
        requireNonBlank(scenario, "scenario", 60);
        if (requestedItemCount < 1 || requestedItemCount > 100) {
            throw new IllegalArgumentException("requestedItemCount must be between 1 and 100");
        }
        requireNonBlank(idempotencyKey, "idempotencyKey", 200);
        requireSha256(manifestHash, "manifestHash");

        DocumentBatch batch = new DocumentBatch();
        batch.product = product;
        batch.owner = owner;
        batch.scenario = scenario;
        batch.requestedItemCount = requestedItemCount;
        batch.idempotencyKey = idempotencyKey;
        batch.manifestHash = manifestHash.toLowerCase();
        batch.status = DocumentBatchStatus.PENDING;
        batch.validateState();
        return batch;
    }

    public void requestCancellation(OffsetDateTime requestedAt, String reason) {
        validateState();
        requireActive();
        requireNonNull(requestedAt, "requestedAt");
        requireNonBlank(reason, "reason", 500);
        if (cancelRequestedAt == null) {
            cancelRequestedAt = requestedAt;
            cancellationReason = reason;
        }
        validateState();
    }

    public void markSucceeded(OffsetDateTime finishedAt) {
        transitionToTerminal(DocumentBatchStatus.SUCCEEDED, finishedAt, null);
    }

    public void cancel(OffsetDateTime finishedAt, String reason) {
        requireNonBlank(reason, "reason", 500);
        transitionToTerminal(DocumentBatchStatus.CANCELLED, finishedAt, reason);
    }

    public void quarantine(OffsetDateTime finishedAt, String reason) {
        requireNonBlank(reason, "reason", 500);
        transitionToTerminal(DocumentBatchStatus.QUARANTINED, finishedAt, reason);
    }

    public Long getProductId() {
        return product.getId();
    }

    public Long getOwnerId() {
        return owner.getId();
    }

    public void validateState() {
        requireNonNull(status, "status");
        boolean terminal = status.isTerminal();
        if (terminal != (terminalAt != null)) {
            throw new IllegalStateException("terminalAt must be set exactly for terminal batches");
        }
        if (status == DocumentBatchStatus.CANCELLED && cancellationReason == null) {
            throw new IllegalStateException("cancelled batch requires cancellationReason");
        }
        if ((status == DocumentBatchStatus.CANCELLED) != (cancelledAt != null)) {
            throw new IllegalStateException("cancelledAt must be set exactly for cancelled batches");
        }
        if (cancelledAt != null
                && (!cancelledAt.isEqual(terminalAt) || cancelledAt.isBefore(cancelRequestedAt))) {
            throw new IllegalStateException("cancelledAt must match terminalAt and follow the request");
        }
        if (status != DocumentBatchStatus.CANCELLED && terminal && cancellationReason != null) {
            throw new IllegalStateException("only cancelled batch may have terminal cancellationReason");
        }
        if (status == DocumentBatchStatus.QUARANTINED && quarantineReason == null) {
            throw new IllegalStateException("quarantined batch requires quarantineReason");
        }
        boolean quarantined = status == DocumentBatchStatus.QUARANTINED;
        if ((quarantined && (quarantineReason == null || quarantinedAt == null))
                || (!quarantined && (quarantineReason != null || quarantinedAt != null))) {
            throw new IllegalStateException("quarantine fields must be set exactly for quarantined batches");
        }
        if (quarantinedAt != null && !quarantinedAt.isEqual(terminalAt)) {
            throw new IllegalStateException("quarantinedAt must match terminalAt");
        }
        if ((cancelRequestedAt == null) != (cancellationReason == null)) {
            throw new IllegalStateException("cancellation request fields must be set together");
        }
    }

    private void transitionToTerminal(
            DocumentBatchStatus terminalStatus,
            OffsetDateTime finishedAt,
            String reason
    ) {
        validateState();
        requireActive();
        requireNonNull(finishedAt, "finishedAt");
        if (terminalStatus != DocumentBatchStatus.CANCELLED && cancelRequestedAt != null) {
            throw new IllegalStateException("batch with a cancellation request must be cancelled");
        }
        if (terminalStatus == DocumentBatchStatus.CANCELLED
                && cancelRequestedAt != null
                && finishedAt.isBefore(cancelRequestedAt)) {
            throw new IllegalArgumentException("finishedAt must not be before cancelRequestedAt");
        }
        status = terminalStatus;
        terminalAt = finishedAt;
        if (terminalStatus == DocumentBatchStatus.CANCELLED) {
            cancelRequestedAt = cancelRequestedAt == null ? finishedAt : cancelRequestedAt;
            cancellationReason = reason;
            cancelledAt = finishedAt;
        } else if (terminalStatus == DocumentBatchStatus.QUARANTINED) {
            quarantineReason = reason;
            quarantinedAt = finishedAt;
        }
        validateState();
    }

    private void requireActive() {
        if (status != DocumentBatchStatus.PENDING) {
            throw new IllegalStateException("terminal batch cannot transition again");
        }
    }

    private static void requirePersistedId(Long id, String fieldName) {
        if (id == null) {
            throw new IllegalArgumentException(fieldName + " must be persisted");
        }
    }

    private static void requireSha256(String value, String fieldName) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " must be a 64-character SHA-256 hex value");
        }
    }

    private static void requireNonBlank(String value, String fieldName, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " must not exceed " + maximumLength + " characters");
        }
    }

    private static void requireNonNull(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
    }
}
