package com.crosschecklab.domain.document.batch;

import com.crosschecklab.domain.document.ProductDocument;
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
        name = "document_batch_items",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "ux_document_batch_items_batch_ordinal",
                        columnNames = {"batch_id", "ordinal"}),
                @UniqueConstraint(
                        name = "ux_document_batch_items_batch_document",
                        columnNames = {"batch_id", "product_document_id"})
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentBatchItem extends BaseTimeEntity {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false, updatable = false)
    private DocumentBatch batch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_document_id", nullable = false, updatable = false)
    private ProductDocument productDocument;

    @Column(nullable = false, updatable = false)
    private int ordinal;

    @Column(name = "source_file_name", nullable = false, updatable = false, length = 255)
    private String sourceFileName;

    @Column(name = "source_media_type", nullable = false, updatable = false, length = 150)
    private String sourceMediaType;

    @Column(name = "source_file_size", nullable = false, updatable = false)
    private long sourceFileSize;

    @Column(name = "source_checksum", nullable = false, updatable = false, length = 64)
    private String sourceChecksum;

    @Column(name = "source_storage_key", nullable = false, updatable = false, length = 500)
    private String sourceStorageKey;

    @Column(name = "due_at", nullable = false)
    private OffsetDateTime dueAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false, updatable = false)
    private int maxAttempts;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentBatchItemStatus status;

    @Column(name = "lease_owner", length = 100)
    private String leaseOwner;

    @Column(name = "lease_until")
    private OffsetDateTime leaseUntil;

    @Column(name = "lease_fence", nullable = false)
    private long leaseFence;

    @Column(name = "cancel_requested_at")
    private OffsetDateTime cancelRequestedAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "last_error_code", length = 60)
    private String lastErrorCode;

    @Column(name = "last_error_message", length = 1000)
    private String lastErrorMessage;

    @Column(name = "quarantine_reason", length = 500)
    private String quarantineReason;

    @Column(name = "quarantined_at")
    private OffsetDateTime quarantinedAt;

    @Column(name = "terminal_at")
    private OffsetDateTime terminalAt;

    public static DocumentBatchItem create(
            DocumentBatch batch,
            ProductDocument productDocument,
            int ordinal,
            OffsetDateTime dueAt,
            int maxAttempts
    ) {
        requireNonNull(batch, "batch");
        requireNonNull(productDocument, "productDocument");
        batch.validateState();
        if (batch.getStatus() != DocumentBatchStatus.PENDING) {
            throw new IllegalArgumentException("items may only be added to a pending batch");
        }
        if (ordinal < 1 || ordinal > batch.getRequestedItemCount()) {
            throw new IllegalArgumentException("ordinal must be between 1 and requestedItemCount");
        }
        requireNonNull(dueAt, "dueAt");
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 100");
        }
        if (!Objects.equals(batch.getProductId(), productDocument.getProductId())) {
            throw new IllegalArgumentException("document and batch must belong to the same product");
        }
        if (!Objects.equals(batch.getOwnerId(), productDocument.getOwnerId())) {
            throw new IllegalArgumentException("document and batch must belong to the same owner");
        }
        requireNonBlank(productDocument.getFileName(), "sourceFileName", 255);
        requireNonBlank(productDocument.getMediaType(), "sourceMediaType", 150);
        Long fileSize = productDocument.getFileSize();
        if (fileSize == null || fileSize < 0) {
            throw new IllegalArgumentException("sourceFileSize must be non-negative");
        }
        requireSha256(productDocument.getChecksum(), "sourceChecksum");
        requireNonBlank(productDocument.getStorageKey(), "sourceStorageKey", 500);

        DocumentBatchItem item = new DocumentBatchItem();
        item.batch = batch;
        item.product = batch.getProduct();
        item.owner = batch.getOwner();
        item.productDocument = productDocument;
        item.ordinal = ordinal;
        item.sourceFileName = productDocument.getFileName();
        item.sourceMediaType = productDocument.getMediaType();
        item.sourceFileSize = fileSize;
        item.sourceChecksum = productDocument.getChecksum().toLowerCase();
        item.sourceStorageKey = productDocument.getStorageKey();
        item.dueAt = dueAt;
        item.maxAttempts = maxAttempts;
        item.status = DocumentBatchItemStatus.PENDING;
        item.attemptCount = 0;
        item.leaseFence = 0;
        item.validateState();
        return item;
    }

    public long lease(String worker, OffsetDateTime now, OffsetDateTime until) {
        validateState();
        requireNonBlank(worker, "worker", 100);
        requireNonNull(now, "now");
        requireNonNull(until, "until");
        if (!status.isClaimable() || dueAt.isAfter(now)) {
            throw new IllegalStateException("item is not due and claimable");
        }
        if (cancelRequestedAt != null) {
            throw new IllegalStateException("item has a pending cancellation request");
        }
        if (attemptCount >= maxAttempts) {
            throw new IllegalStateException("item has exhausted its attempts");
        }
        if (!until.isAfter(now)) {
            throw new IllegalArgumentException("until must be after now");
        }

        long nextFence = Math.addExact(leaseFence, 1L);
        status = DocumentBatchItemStatus.LEASED;
        leaseOwner = worker;
        leaseUntil = until;
        leaseFence = nextFence;
        attemptCount++;
        validateState();
        return leaseFence;
    }

    public void renewLease(
            String worker,
            long expectedFence,
            OffsetDateTime now,
            OffsetDateTime until
    ) {
        validateLease(worker, expectedFence, now);
        requireNoCancellationRequest();
        requireNonNull(until, "until");
        if (!until.isAfter(now)) {
            throw new IllegalArgumentException("until must be after now");
        }
        leaseUntil = until;
        validateState();
    }

    public void retry(
            String worker,
            long expectedFence,
            OffsetDateTime now,
            OffsetDateTime nextDueAt,
            String errorCode,
            String errorMessage
    ) {
        validateLease(worker, expectedFence, now);
        requireNoCancellationRequest();
        requireNonNull(nextDueAt, "nextDueAt");
        requireError(errorCode, errorMessage);
        if (attemptCount >= maxAttempts) {
            throw new IllegalStateException("exhausted item must be quarantined");
        }
        if (!nextDueAt.isAfter(now)) {
            throw new IllegalArgumentException("nextDueAt must be after now");
        }

        status = DocumentBatchItemStatus.RETRY_WAIT;
        dueAt = nextDueAt;
        lastErrorCode = errorCode;
        lastErrorMessage = errorMessage;
        clearLease();
        validateState();
    }

    public void succeed(String worker, long expectedFence, OffsetDateTime finishedAt) {
        validateLease(worker, expectedFence, finishedAt);
        requireNoCancellationRequest();
        status = DocumentBatchItemStatus.SUCCEEDED;
        terminalAt = finishedAt;
        lastErrorCode = null;
        lastErrorMessage = null;
        clearLease();
        validateState();
    }

    public void quarantine(
            String worker,
            long expectedFence,
            OffsetDateTime finishedAt,
            String errorCode,
            String errorMessage,
            String reason
    ) {
        validateLease(worker, expectedFence, finishedAt);
        requireNoCancellationRequest();
        requireError(errorCode, errorMessage);
        requireNonBlank(reason, "reason", 500);
        status = DocumentBatchItemStatus.QUARANTINED;
        terminalAt = finishedAt;
        quarantinedAt = finishedAt;
        quarantineReason = reason;
        lastErrorCode = errorCode;
        lastErrorMessage = errorMessage;
        clearLease();
        validateState();
    }

    public void requestCancellation(OffsetDateTime requestedAt, String reason) {
        validateState();
        requireNonTerminal();
        requireNonNull(requestedAt, "requestedAt");
        requireNonBlank(reason, "reason", 500);
        if (cancelRequestedAt == null) {
            cancelRequestedAt = requestedAt;
            cancellationReason = reason;
        }
        validateState();
    }

    public void cancel(OffsetDateTime finishedAt, String reason) {
        validateState();
        requireNonTerminal();
        requireNonNull(finishedAt, "finishedAt");
        requireNonBlank(reason, "reason", 500);
        if (cancelRequestedAt != null && finishedAt.isBefore(cancelRequestedAt)) {
            throw new IllegalArgumentException("finishedAt must not be before cancelRequestedAt");
        }
        status = DocumentBatchItemStatus.CANCELLED;
        cancelRequestedAt = cancelRequestedAt == null ? finishedAt : cancelRequestedAt;
        cancellationReason = reason;
        cancelledAt = finishedAt;
        terminalAt = finishedAt;
        clearLease();
        validateState();
    }

    public void recoverExpiredLease(
            OffsetDateTime now,
            OffsetDateTime nextDueAt,
            String errorCode,
            String errorMessage,
            String exhaustedReason
    ) {
        validateState();
        requireNonNull(now, "now");
        if (status != DocumentBatchItemStatus.LEASED || leaseUntil.isAfter(now)) {
            throw new IllegalStateException("lease is not expired");
        }
        requireError(errorCode, errorMessage);
        boolean cancelling = cancelRequestedAt != null;
        boolean exhausted = attemptCount >= maxAttempts;
        if (cancelling && now.isBefore(cancelRequestedAt)) {
            throw new IllegalArgumentException("now must not be before cancelRequestedAt");
        }
        if (exhausted && !cancelling) {
            requireNonBlank(exhaustedReason, "exhaustedReason", 500);
        }
        if (!cancelling && !exhausted) {
            requireNonNull(nextDueAt, "nextDueAt");
            if (nextDueAt.isBefore(now)) {
                throw new IllegalArgumentException("nextDueAt must not be before now");
            }
        }

        lastErrorCode = errorCode;
        lastErrorMessage = errorMessage;
        clearLease();
        if (cancelling) {
            status = DocumentBatchItemStatus.CANCELLED;
            terminalAt = now;
            cancelledAt = now;
        } else if (exhausted) {
            status = DocumentBatchItemStatus.QUARANTINED;
            terminalAt = now;
            quarantinedAt = now;
            quarantineReason = exhaustedReason;
        } else {
            status = DocumentBatchItemStatus.RETRY_WAIT;
            dueAt = nextDueAt;
        }
        validateState();
    }

    public Long getBatchId() {
        return batch.getId();
    }

    public Long getProductId() {
        return product.getId();
    }

    public Long getOwnerId() {
        return owner.getId();
    }

    public Long getProductDocumentId() {
        return productDocument.getId();
    }

    public void validateState() {
        requireNonNull(status, "status");
        if (maxAttempts < 1 || maxAttempts > 100
                || attemptCount < 0 || attemptCount > maxAttempts) {
            throw new IllegalStateException("attemptCount must be between zero and maxAttempts");
        }
        if (dueAt == null) {
            throw new IllegalStateException("dueAt must not be null");
        }
        if (leaseFence < 0) {
            throw new IllegalStateException("leaseFence must not be negative");
        }
        boolean leased = status == DocumentBatchItemStatus.LEASED;
        if ((leased && (leaseOwner == null || leaseUntil == null))
                || (!leased && (leaseOwner != null || leaseUntil != null))) {
            throw new IllegalStateException("lease fields must be set exactly for leased items");
        }
        boolean terminal = status.isTerminal();
        if (terminal != (terminalAt != null)) {
            throw new IllegalStateException("terminalAt must be set exactly for terminal items");
        }
        if ((cancelRequestedAt == null) != (cancellationReason == null)) {
            throw new IllegalStateException("cancellation fields must be set together");
        }
        if (status == DocumentBatchItemStatus.CANCELLED && cancellationReason == null) {
            throw new IllegalStateException("cancelled item requires cancellationReason");
        }
        if ((status == DocumentBatchItemStatus.CANCELLED) != (cancelledAt != null)) {
            throw new IllegalStateException("cancelledAt must be set exactly for cancelled items");
        }
        if (cancelledAt != null
                && (!cancelledAt.isEqual(terminalAt) || cancelledAt.isBefore(cancelRequestedAt))) {
            throw new IllegalStateException("cancelledAt must match terminalAt and follow the request");
        }
        boolean quarantined = status == DocumentBatchItemStatus.QUARANTINED;
        if ((quarantined && (quarantinedAt == null || quarantineReason == null))
                || (!quarantined && (quarantinedAt != null || quarantineReason != null))) {
            throw new IllegalStateException("quarantine fields must be set exactly for quarantined items");
        }
        if (quarantinedAt != null && !quarantinedAt.isEqual(terminalAt)) {
            throw new IllegalStateException("quarantinedAt must match terminalAt");
        }
    }

    private void validateLease(String worker, long expectedFence, OffsetDateTime now) {
        validateState();
        requireNonBlank(worker, "worker", 100);
        requireNonNull(now, "now");
        if (status != DocumentBatchItemStatus.LEASED
                || !Objects.equals(leaseOwner, worker)
                || leaseFence != expectedFence) {
            throw new IllegalStateException("lease owner or fence does not match");
        }
        if (!leaseUntil.isAfter(now)) {
            throw new IllegalStateException("lease has expired");
        }
    }

    private void requireNoCancellationRequest() {
        if (cancelRequestedAt != null) {
            throw new IllegalStateException("item with a cancellation request must be cancelled");
        }
    }

    private void requireNonTerminal() {
        if (status.isTerminal()) {
            throw new IllegalStateException("terminal item cannot transition again");
        }
    }

    private void clearLease() {
        leaseOwner = null;
        leaseUntil = null;
    }

    private static void requireError(String errorCode, String errorMessage) {
        requireNonBlank(errorCode, "errorCode", 60);
        requireNonBlank(errorMessage, "errorMessage", 1000);
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
