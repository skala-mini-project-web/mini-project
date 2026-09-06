package com.crosschecklab.domain.analysis;

import com.crosschecklab.domain.review.FindingReviewDecision;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Getter
@Table(
        name = "risk_score_runs",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_risk_score_runs_idempotency",
                columnNames = {"analysis_execution_id", "policy_version", "input_fingerprint"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiskScoreRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analysis_execution_id", nullable = false, updatable = false)
    private AnalysisExecution analysisExecution;

    @Column(name = "policy_version", nullable = false, updatable = false, length = 100)
    private String policyVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private RiskScoreState state;

    @Column(name = "score_value", updatable = false)
    private Integer scoreValue;

    @Column(name = "not_scored_reason", updatable = false, length = 500)
    private String notScoredReason;

    @Column(name = "input_fingerprint", nullable = false, updatable = false, length = 64)
    private String inputFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at", updatable = false)
    private OffsetDateTime completedAt;

    @OneToMany(mappedBy = "riskScoreRun", cascade = CascadeType.PERSIST)
    @OrderBy("id ASC")
    private List<RiskScoreLedgerEntry> ledgerEntries = new ArrayList<>();

    public static RiskScoreRun pendingReview(
            AnalysisExecution analysisExecution,
            String policyVersion,
            String inputFingerprint,
            OffsetDateTime createdAt
    ) {
        return create(
                analysisExecution,
                policyVersion,
                RiskScoreState.PENDING_REVIEW,
                null,
                null,
                inputFingerprint,
                createdAt,
                null);
    }

    public static RiskScoreRun notScored(
            AnalysisExecution analysisExecution,
            String policyVersion,
            String inputFingerprint,
            String notScoredReason,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt
    ) {
        return create(
                analysisExecution,
                policyVersion,
                RiskScoreState.NOT_SCORED,
                null,
                notScoredReason,
                inputFingerprint,
                createdAt,
                completedAt);
    }

    public static RiskScoreRun scored(
            AnalysisExecution analysisExecution,
            String policyVersion,
            String inputFingerprint,
            int scoreValue,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt
    ) {
        return create(
                analysisExecution,
                policyVersion,
                RiskScoreState.SCORED,
                scoreValue,
                null,
                inputFingerprint,
                createdAt,
                completedAt);
    }

    public static RiskScoreRun create(
            AnalysisExecution analysisExecution,
            String policyVersion,
            RiskScoreState state,
            Integer scoreValue,
            String notScoredReason,
            String inputFingerprint,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt
    ) {
        requirePersistedSuccessfulExecution(analysisExecution);
        requireNonBlank(policyVersion, "policyVersion", 100);
        requireSha256(inputFingerprint, "inputFingerprint");
        requireNonNull(state, "state");
        requireNonNull(createdAt, "createdAt");
        validateResult(state, scoreValue, notScoredReason, completedAt);
        if (completedAt != null && completedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("completedAt must not be before createdAt");
        }

        RiskScoreRun run = new RiskScoreRun();
        run.analysisExecution = analysisExecution;
        run.policyVersion = policyVersion;
        run.state = state;
        run.scoreValue = scoreValue;
        run.notScoredReason = notScoredReason;
        run.inputFingerprint = inputFingerprint;
        run.createdAt = createdAt;
        run.completedAt = completedAt;
        return run;
    }

    public void addLedgerEntry(
            Finding findingRevision,
            FindingReviewDecision reviewDecision,
            FindingEvidenceAnchor documentClaimAnchor,
            FindingEvidenceAnchor policyRequirementAnchor,
            String policyRuleId,
            int magnitudeBasisPoints,
            int likelihoodBasisPoints,
            int contributionBasisPoints,
            OffsetDateTime createdAt
    ) {
        if (state != RiskScoreState.SCORED) {
            throw new IllegalStateException("ledger entries require a scored run");
        }
        RiskScoreLedgerEntry entry = RiskScoreLedgerEntry.create(
                this,
                findingRevision,
                reviewDecision,
                documentClaimAnchor,
                policyRequirementAnchor,
                policyRuleId,
                magnitudeBasisPoints,
                likelihoodBasisPoints,
                contributionBasisPoints,
                createdAt);
        boolean duplicate = ledgerEntries.stream().anyMatch(existing ->
                existing.hasSameHarmEvent(documentClaimAnchor, policyRuleId));
        if (duplicate) {
            throw new IllegalArgumentException("document claim anchor and policyRuleId must be unique within a run");
        }
        ledgerEntries.add(entry);
    }

    public List<RiskScoreLedgerEntry> getLedgerEntries() {
        return List.copyOf(ledgerEntries);
    }

    private static void validateResult(
            RiskScoreState state,
            Integer scoreValue,
            String notScoredReason,
            OffsetDateTime completedAt
    ) {
        switch (state) {
            case PENDING_REVIEW -> {
                if (scoreValue != null || notScoredReason != null || completedAt != null) {
                    throw new IllegalArgumentException(
                            "pending review runs cannot have a score, reason, or completion timestamp");
                }
            }
            case NOT_SCORED -> {
                if (scoreValue != null) {
                    throw new IllegalArgumentException("not-scored runs cannot have a numeric score");
                }
                requireNonBlank(notScoredReason, "notScoredReason", 500);
                requireNonNull(completedAt, "completedAt");
            }
            case SCORED -> {
                if (scoreValue == null || scoreValue < 0 || scoreValue > 100) {
                    throw new IllegalArgumentException("scoreValue must be between 0 and 100 for a scored run");
                }
                if (notScoredReason != null) {
                    throw new IllegalArgumentException("scored runs cannot have a not-scored reason");
                }
                requireNonNull(completedAt, "completedAt");
            }
        }
    }

    private static void requirePersistedSuccessfulExecution(AnalysisExecution execution) {
        if (execution == null || execution.getId() == null) {
            throw new IllegalArgumentException("analysisExecution must be persisted");
        }
        if (!execution.isSucceeded()) {
            throw new IllegalArgumentException("analysisExecution must be successful");
        }
    }

    private static void requireSha256(String value, String fieldName) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(fieldName + " must be lowercase SHA-256 hex");
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
