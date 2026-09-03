package com.crosschecklab.domain.review.dto;

import com.crosschecklab.domain.review.Review;
import com.crosschecklab.global.common.enums.ReviewStatus;
import java.time.OffsetDateTime;
import java.util.List;

// REV-003 응답 (200). riskPatternIds 는 승인 시 승격된 패턴이며 반려면 빈 배열이다.
public record ReviewDecisionResponse(
        Long reviewId,
        ReviewStatus status,
        Long reviewerId,
        List<Long> riskPatternIds,
        OffsetDateTime decidedAt
) {

    public static ReviewDecisionResponse of(Review review, List<Long> riskPatternIds) {
        return new ReviewDecisionResponse(
                review.getId(), review.getStatus(), review.getReviewerId(), riskPatternIds, review.getDecidedAt());
    }
}
