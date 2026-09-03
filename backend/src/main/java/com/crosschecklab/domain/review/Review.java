package com.crosschecklab.domain.review;

import com.crosschecklab.global.common.BaseTimeEntity;
import com.crosschecklab.global.common.enums.ReviewStatus;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 분석 1건에 대한 사람 검토 (REV-001~003). analysis_id 는 UNIQUE 라 분석당 검토는 하나뿐이다.
// 승인/반려는 별도 decision 컬럼 없이 status 하나로 관리한다(API 명세 §10 확정 사항).
// 승인 시 선택한 Finding 은 review_selected_findings 에 남겨 RiskPattern 을 역추적할 수 있게 한다.
@Entity
@Getter
@Table(name = "reviews")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "analysis_id", nullable = false, updatable = false, unique = true)
    private Long analysisId;

    // 결정 전에는 담당 검토자가 정해져 있지 않다. 결정한 사람이 그대로 기록된다.
    @Column(name = "reviewer_id")
    private Long reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewStatus status;

    @Column(length = 1000)
    private String comment;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    // 조인 테이블이 (review_id, finding_id) 쌍뿐이라 별도 엔티티 대신 @ElementCollection 으로 둔다.
    @ElementCollection
    @CollectionTable(name = "review_selected_findings", joinColumns = @JoinColumn(name = "review_id"))
    @Column(name = "finding_id", nullable = false)
    private Set<Long> selectedFindingIds = new LinkedHashSet<>();

    public static Review create(Long analysisId) {
        Review review = new Review();
        review.analysisId = analysisId;
        review.status = ReviewStatus.PENDING;
        return review;
    }

    // REV-003. 결정은 1회뿐이며 재처리는 409 로 끊는다.
    public void decide(ReviewStatus decision, Long reviewerId, String comment,
                       Set<Long> selectedFindingIds, OffsetDateTime decidedAt) {
        if (status != ReviewStatus.PENDING) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_DECIDED);
        }
        this.status = decision;
        this.reviewerId = reviewerId;
        this.comment = comment;
        this.decidedAt = decidedAt;
        // 반려한 Finding 은 승격 대상이 아니므로 선택 목록에 남기지 않는다.
        this.selectedFindingIds = decision == ReviewStatus.APPROVED
                ? new LinkedHashSet<>(selectedFindingIds)
                : new LinkedHashSet<>();
    }
}
