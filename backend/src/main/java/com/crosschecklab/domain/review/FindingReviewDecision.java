package com.crosschecklab.domain.review;

import com.crosschecklab.domain.analysis.Finding;
import com.crosschecklab.global.common.enums.ReviewStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(
        name = "finding_review_decisions",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_finding_review_decisions_review_finding",
                columnNames = {"review_id", "finding_revision_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FindingReviewDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false, updatable = false)
    private Long reviewId;

    @Column(name = "analysis_execution_id", nullable = false, updatable = false)
    private Long analysisExecutionId;

    @Column(name = "finding_revision_id", nullable = false, updatable = false)
    private Long findingRevisionId;

    @Column(name = "reviewer_id", nullable = false, updatable = false)
    private Long reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private ReviewStatus decision;

    @Column(length = 1000, updatable = false)
    private String comment;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private OffsetDateTime decidedAt;

    public static FindingReviewDecision create(
            Review review,
            Finding finding,
            Long reviewerId,
            ReviewStatus decision,
            String comment,
            OffsetDateTime decidedAt
    ) {
        if (review == null || review.getId() == null) {
            throw new IllegalArgumentException("review must be persisted");
        }
        if (finding == null || finding.getId() == null || finding.getAnalysisExecution() == null) {
            throw new IllegalArgumentException("finding must be an execution-bound revision");
        }
        Long findingExecutionId = finding.getAnalysisExecution().getId();
        if (findingExecutionId == null
                || !findingExecutionId.equals(review.getAnalysisExecutionId())) {
            throw new IllegalArgumentException("finding must belong to the review execution");
        }
        if (reviewerId == null || decidedAt == null) {
            throw new IllegalArgumentException("reviewerId and decidedAt must not be null");
        }
        if (decision != ReviewStatus.APPROVED && decision != ReviewStatus.REJECTED) {
            throw new IllegalArgumentException("decision must be APPROVED or REJECTED");
        }

        FindingReviewDecision findingDecision = new FindingReviewDecision();
        findingDecision.reviewId = review.getId();
        findingDecision.analysisExecutionId = review.getAnalysisExecutionId();
        findingDecision.findingRevisionId = finding.getId();
        findingDecision.reviewerId = reviewerId;
        findingDecision.decision = decision;
        findingDecision.comment = comment;
        findingDecision.decidedAt = decidedAt;
        return findingDecision;
    }
}
