package com.crosschecklab.domain.analysis;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Durable boundary for one analysis attempt. Identity and retrieval inputs never
 * change, and a terminal execution cannot transition again.
 */
@Entity
@Getter
@Table(
        name = "analysis_executions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "ux_analysis_executions_attempt",
                        columnNames = {"analysis_id", "attempt_no"}),
                @UniqueConstraint(
                        name = "ux_analysis_executions_token",
                        columnNames = "execution_token")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisExecution {

    public enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED,
        DISCARDED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analysis_id", nullable = false, updatable = false)
    private Analysis analysis;

    @Column(name = "attempt_no", nullable = false, updatable = false)
    private int attemptNo;

    @Column(name = "execution_token", nullable = false, updatable = false, length = 36)
    private String executionToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "error_code", length = 60)
    private String errorCode;

    @Column(nullable = false)
    private boolean retryable;

    @Column(name = "retrieval_version", nullable = false, updatable = false, length = 100)
    private String retrievalVersion;

    @Column(name = "provider_risk_score")
    private Integer providerRiskScore;

    @Column(name = "model_version", length = 50)
    private String modelVersion;

    @Column(name = "prompt_version", length = 50)
    private String promptVersion;

    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static AnalysisExecution start(
            Analysis analysis,
            int attemptNo,
            String executionToken,
            String retrievalVersion,
            OffsetDateTime startedAt
    ) {
        requireNonNull(analysis, "analysis");
        if (attemptNo <= 0) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        requireNonBlank(executionToken, "executionToken", 36);
        requireNonBlank(retrievalVersion, "retrievalVersion", 100);
        requireNonNull(startedAt, "startedAt");

        AnalysisExecution execution = new AnalysisExecution();
        execution.analysis = analysis;
        execution.attemptNo = attemptNo;
        execution.executionToken = executionToken;
        execution.retrievalVersion = retrievalVersion;
        execution.status = Status.RUNNING;
        execution.retryable = false;
        execution.startedAt = startedAt;
        execution.createdAt = startedAt;
        return execution;
    }

    public void succeed(
            int providerRiskScore,
            String modelVersion,
            String promptVersion,
            OffsetDateTime finishedAt
    ) {
        requireRunning();
        requireNonBlank(modelVersion, "modelVersion", 50);
        requireNonBlank(promptVersion, "promptVersion", 50);
        requireValidFinishedAt(finishedAt);

        this.status = Status.SUCCEEDED;
        this.providerRiskScore = providerRiskScore;
        this.modelVersion = modelVersion;
        this.promptVersion = promptVersion;
        this.finishedAt = finishedAt;
        this.errorCode = null;
        this.retryable = false;
    }

    public void fail(String errorCode, boolean retryable, OffsetDateTime finishedAt) {
        requireRunning();
        requireNonBlank(errorCode, "errorCode", 60);
        requireValidFinishedAt(finishedAt);

        this.status = Status.FAILED;
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.finishedAt = finishedAt;
        clearProviderResult();
    }

    public void discard(String errorCode, OffsetDateTime finishedAt) {
        requireRunning();
        requireNonBlank(errorCode, "errorCode", 60);
        requireValidFinishedAt(finishedAt);

        this.status = Status.DISCARDED;
        this.errorCode = errorCode;
        this.retryable = false;
        this.finishedAt = finishedAt;
        clearProviderResult();
    }

    public boolean isSucceeded() {
        return status == Status.SUCCEEDED;
    }

    public boolean isRunning() {
        return status == Status.RUNNING;
    }

    boolean belongsTo(Analysis candidate) {
        if (analysis == candidate) {
            return true;
        }
        return analysis != null
                && candidate != null
                && analysis.getId() != null
                && Objects.equals(analysis.getId(), candidate.getId());
    }

    private void requireRunning() {
        if (status != Status.RUNNING) {
            throw new IllegalStateException("terminal execution cannot transition again");
        }
    }

    private void requireValidFinishedAt(OffsetDateTime value) {
        requireNonNull(value, "finishedAt");
        if (value.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt must not be before startedAt");
        }
    }

    private void clearProviderResult() {
        this.providerRiskScore = null;
        this.modelVersion = null;
        this.promptVersion = null;
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
