package com.crosschecklab.domain.review.dto;

import com.crosschecklab.domain.review.Review;
import com.crosschecklab.global.common.enums.ReviewStatus;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * REV-004 응답. 분석 하나에 대한 검토 결과를 상품 담당자와 검토자가 함께 보는 화면용이다.
 *
 * <p>반려 사유(comment)를 담당자에게 전달하는 유일한 경로다. 담당자는 이 응답을 보고
 * 추출 텍스트를 고쳐 새 분석을 만든다. PENDING 이면 reviewerId·decidedAt·comment 가 모두 null 이다.
 *
 * <p>selectedFindingIds 는 승인 시 RiskPattern 으로 승격된 Finding 이다. 반려면 비어 있다.
 */
public record ReviewOutcomeResponse(
        Long reviewId,
        Long analysisId,
        ReviewStatus status,
        String comment,
        Long reviewerId,
        OffsetDateTime decidedAt,
        List<Long> selectedFindingIds,
        OffsetDateTime createdAt
) {

    public static ReviewOutcomeResponse from(Review review) {
        return new ReviewOutcomeResponse(
                review.getId(),
                review.getAnalysisId(),
                review.getStatus(),
                review.getComment(),
                review.getReviewerId(),
                review.getDecidedAt(),
                List.copyOf(review.getSelectedFindingIds()),
                review.getCreatedAt());
    }
}
