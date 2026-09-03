package com.crosschecklab.domain.review.dto;

import com.crosschecklab.domain.review.ReviewListRow;
import com.crosschecklab.global.common.enums.ReviewStatus;
import com.crosschecklab.global.common.enums.Severity;

// REV-002 목록 한 건. maxSeverity 는 분석의 Finding 중 가장 높은 위험도이며 Finding 이 없으면 null 이다.
public record ReviewListItemResponse(
        Long reviewId,
        Long analysisId,
        String productName,
        Severity maxSeverity,
        ReviewStatus status,
        String ownerName
) {

    public static ReviewListItemResponse from(ReviewListRow row) {
        return new ReviewListItemResponse(
                row.getReviewId(),
                row.getAnalysisId(),
                row.getProductName(),
                row.getMaxSeverity() == null ? null : Severity.valueOf(row.getMaxSeverity()),
                ReviewStatus.valueOf(row.getStatus()),
                row.getOwnerName());
    }
}
