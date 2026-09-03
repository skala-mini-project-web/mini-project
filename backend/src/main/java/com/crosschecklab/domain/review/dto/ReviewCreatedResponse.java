package com.crosschecklab.domain.review.dto;

import com.crosschecklab.domain.review.Review;
import com.crosschecklab.global.common.enums.ReviewStatus;
import java.time.OffsetDateTime;

// REV-001 응답 (201)
public record ReviewCreatedResponse(
        Long reviewId,
        Long analysisId,
        ReviewStatus status,
        OffsetDateTime createdAt
) {

    public static ReviewCreatedResponse from(Review review) {
        return new ReviewCreatedResponse(
                review.getId(), review.getAnalysisId(), review.getStatus(), review.getCreatedAt());
    }
}
