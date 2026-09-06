package com.crosschecklab.domain.analysis;

import com.crosschecklab.domain.review.FindingReviewDecision;
import com.crosschecklab.global.common.enums.ReviewStatus;
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
import java.util.Objects;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Getter
@Table(
        name = "risk_score_ledger_entries",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_risk_score_ledger_entries_harm_event",
                columnNames = {"risk_score_run_id", "document_claim_anchor_id", "policy_rule_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiskScoreLedgerEntry {

    private static final Set<Integer> POLICY_COMPONENTS = Set.of(2000, 4000, 6000, 8000, 10000);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "risk_score_run_id", nullable = false, updatable = false)
    private RiskScoreRun riskScoreRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "finding_revision_id", nullable = false, updatable = false)
    private Finding findingRevision;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "finding_review_decision_id", nullable = false, updatable = false)
    private FindingReviewDecision findingReviewDecision;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_claim_anchor_id", nullable = false, updatable = false)
    private FindingEvidenceAnchor documentClaimAnchor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_requirement_anchor_id", nullable = false, updatable = false)
    private FindingEvidenceAnchor policyRequirementAnchor;

    @Column(name = "policy_rule_id", nullable = false, updatable = false, length = 100)
    private String policyRuleId;

    @Column(name = "magnitude_basis_points", nullable = false, updatable = false)
    private int magnitudeBasisPoints;

    @Column(name = "likelihood_basis_points", nullable = false, updatable = false)
    private int likelihoodBasisPoints;

    @Column(name = "contribution_basis_points", nullable = false, updatable = false)
    private int contributionBasisPoints;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static RiskScoreLedgerEntry create(
            RiskScoreRun riskScoreRun,
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
        requireScoredRun(riskScoreRun);
        requireEligibleFinding(riskScoreRun, findingRevision);
        requireApproval(reviewDecision, findingRevision, riskScoreRun);
        requireAnchor(documentClaimAnchor, findingRevision, FindingEvidenceAnchor.SourceRole.DOCUMENT_CLAIM);
        requireAnchor(
                policyRequirementAnchor,
                findingRevision,
                FindingEvidenceAnchor.SourceRole.POLICY_REQUIREMENT);
        requireNonBlank(policyRuleId, "policyRuleId", 100);
        requirePolicyComponent(magnitudeBasisPoints, "magnitudeBasisPoints");
        requirePolicyComponent(likelihoodBasisPoints, "likelihoodBasisPoints");
        int expectedContribution = magnitudeBasisPoints * likelihoodBasisPoints / 10000;
        if (contributionBasisPoints != expectedContribution) {
            throw new IllegalArgumentException(
                    "contributionBasisPoints must equal magnitudeBasisPoints * likelihoodBasisPoints / 10000");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
        if (createdAt.isBefore(riskScoreRun.getCreatedAt())
                || createdAt.isAfter(riskScoreRun.getCompletedAt())) {
            throw new IllegalArgumentException("createdAt must be within the score run time range");
        }

        RiskScoreLedgerEntry entry = new RiskScoreLedgerEntry();
        entry.riskScoreRun = riskScoreRun;
        entry.findingRevision = findingRevision;
        entry.findingReviewDecision = reviewDecision;
        entry.documentClaimAnchor = documentClaimAnchor;
        entry.policyRequirementAnchor = policyRequirementAnchor;
        entry.policyRuleId = policyRuleId;
        entry.magnitudeBasisPoints = magnitudeBasisPoints;
        entry.likelihoodBasisPoints = likelihoodBasisPoints;
        entry.contributionBasisPoints = contributionBasisPoints;
        entry.createdAt = createdAt;
        return entry;
    }

    boolean hasSameHarmEvent(FindingEvidenceAnchor anchor, String candidatePolicyRuleId) {
        return anchor != null
                && Objects.equals(documentClaimAnchor.getId(), anchor.getId())
                && Objects.equals(policyRuleId, candidatePolicyRuleId);
    }

    private static void requireScoredRun(RiskScoreRun run) {
        if (run == null || run.getState() != RiskScoreState.SCORED) {
            throw new IllegalArgumentException("riskScoreRun must be scored");
        }
    }

    private static void requireEligibleFinding(RiskScoreRun run, Finding finding) {
        if (finding == null
                || finding.getId() == null
                || finding.getAnalysisExecution() == null
                || finding.getAnalysisExecution().getId() == null
                || finding.getLineageId() == null
                || finding.getRevisionNumber() == null) {
            throw new IllegalArgumentException("findingRevision must be a persisted execution-bound revision");
        }
        if (!finding.getAnalysisExecution().getId().equals(run.getAnalysisExecution().getId())) {
            throw new IllegalArgumentException("findingRevision must belong to the score run execution");
        }
    }

    private static void requireApproval(
            FindingReviewDecision decision,
            Finding finding,
            RiskScoreRun run
    ) {
        if (decision == null || decision.getId() == null) {
            throw new IllegalArgumentException("reviewDecision must be persisted");
        }
        if (decision.getDecision() != ReviewStatus.APPROVED) {
            throw new IllegalArgumentException("reviewDecision must approve the finding revision");
        }
        if (!Objects.equals(decision.getFindingRevisionId(), finding.getId())
                || !Objects.equals(
                        decision.getAnalysisExecutionId(),
                        run.getAnalysisExecution().getId())) {
            throw new IllegalArgumentException("reviewDecision must approve the score run finding revision");
        }
    }

    private static void requireAnchor(
            FindingEvidenceAnchor anchor,
            Finding finding,
            FindingEvidenceAnchor.SourceRole requiredRole
    ) {
        if (anchor == null || anchor.getId() == null) {
            throw new IllegalArgumentException(requiredRole + " anchor must be persisted");
        }
        if (anchor.getSourceRole() != requiredRole
                || anchor.getFinding() == null
                || !Objects.equals(anchor.getFinding().getId(), finding.getId())) {
            throw new IllegalArgumentException(requiredRole + " anchor must belong to the finding revision");
        }
    }

    private static void requirePolicyComponent(int value, String fieldName) {
        if (!POLICY_COMPONENTS.contains(value)) {
            throw new IllegalArgumentException(
                    fieldName + " must be one of 2000, 4000, 6000, 8000, or 10000 basis points");
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
