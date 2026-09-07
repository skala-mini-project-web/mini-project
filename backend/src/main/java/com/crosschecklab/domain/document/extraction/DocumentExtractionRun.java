package com.crosschecklab.domain.document.extraction;

import com.crosschecklab.domain.document.ProductDocument;
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
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A terminal, append-only record of one deterministic document extraction attempt. */
@Entity
@Immutable
@Getter
@Table(
        name = "document_extraction_runs",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_document_extraction_runs_document_generation",
                columnNames = {"product_document_id", "run_generation"})
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentExtractionRun {

    public enum State {
        SUCCEEDED,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_document_id", nullable = false, updatable = false)
    private ProductDocument productDocument;

    @Column(name = "run_generation", nullable = false, updatable = false)
    private int runGeneration;

    @Column(name = "source_hash", nullable = false, updatable = false, length = 64)
    private String sourceHash;

    @Column(name = "config_version", nullable = false, updatable = false, length = 100)
    private String configVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_snapshot", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> configSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private State state;

    @Column(name = "result_text_hash", updatable = false, length = 64)
    private String resultTextHash;

    @Column(name = "page_count", nullable = false, updatable = false)
    private int pageCount;

    @Column(name = "error_code", updatable = false, length = 60)
    private String errorCode;

    @Column(name = "error_message", updatable = false, length = 500)
    private String errorMessage;

    @Column(name = "error_retryable", nullable = false, updatable = false)
    private boolean errorRetryable;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static DocumentExtractionRun succeeded(
            ProductDocument productDocument,
            int runGeneration,
            String sourceHash,
            String configVersion,
            Map<String, Object> configSnapshot,
            String resultTextHash,
            int pageCount
    ) {
        DocumentExtractionRun run = base(
                productDocument, runGeneration, sourceHash, configVersion, configSnapshot, pageCount);
        requireSha256(resultTextHash, "resultTextHash");
        if (pageCount == 0) {
            throw new IllegalArgumentException("a succeeded run must contain at least one page");
        }
        run.state = State.SUCCEEDED;
        run.resultTextHash = resultTextHash;
        run.errorRetryable = false;
        return run;
    }

    public static DocumentExtractionRun failed(
            ProductDocument productDocument,
            int runGeneration,
            String sourceHash,
            String configVersion,
            Map<String, Object> configSnapshot,
            int pageCount,
            String errorCode,
            String errorMessage,
            boolean retryable
    ) {
        DocumentExtractionRun run = base(
                productDocument, runGeneration, sourceHash, configVersion, configSnapshot, pageCount);
        requireNonBlank(errorCode, "errorCode", 60);
        requireNonBlank(errorMessage, "errorMessage", 500);
        run.state = State.FAILED;
        run.errorCode = errorCode;
        run.errorMessage = errorMessage;
        run.errorRetryable = retryable;
        return run;
    }

    public boolean isSucceeded() {
        return state == State.SUCCEEDED;
    }

    public boolean isFailed() {
        return state == State.FAILED;
    }

    public Long getProductDocumentId() {
        return productDocument.getId();
    }

    public boolean belongsTo(ProductDocument document) {
        if (productDocument == document) {
            return true;
        }
        return productDocument != null
                && document != null
                && productDocument.getId() != null
                && Objects.equals(productDocument.getId(), document.getId());
    }

    private static DocumentExtractionRun base(
            ProductDocument productDocument,
            int runGeneration,
            String sourceHash,
            String configVersion,
            Map<String, Object> configSnapshot,
            int pageCount
    ) {
        if (productDocument == null) {
            throw new IllegalArgumentException("productDocument must not be null");
        }
        if (runGeneration <= 0) {
            throw new IllegalArgumentException("runGeneration must be positive");
        }
        requireSha256(sourceHash, "sourceHash");
        requireNonBlank(configVersion, "configVersion", 100);
        if (configSnapshot == null) {
            throw new IllegalArgumentException("configSnapshot must not be null");
        }
        if (pageCount < 0) {
            throw new IllegalArgumentException("pageCount must not be negative");
        }

        DocumentExtractionRun run = new DocumentExtractionRun();
        run.productDocument = productDocument;
        run.runGeneration = runGeneration;
        run.sourceHash = sourceHash;
        run.configVersion = configVersion;
        run.configSnapshot = Map.copyOf(configSnapshot);
        run.pageCount = pageCount;
        return run;
    }

    private static void requireSha256(String value, String fieldName) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(fieldName + " must be a lowercase SHA-256 hex digest");
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
}
