package com.crosschecklab.domain.review.dto;

import com.crosschecklab.domain.analysis.Analysis;
import com.crosschecklab.domain.product.Product;
import com.crosschecklab.domain.review.Review;
import com.crosschecklab.global.common.enums.ReviewStatus;
import com.crosschecklab.global.common.enums.Severity;
import java.time.OffsetDateTime;
import java.util.List;

public record ReviewDetailResponse(
        Long reviewId,
        Long analysisId,
        Long productId,
        String productName,
        Long ownerId,
        String ownerName,
        Severity maxSeverity,
        ReviewStatus status,
        String submissionComment,
        Long reviewerId,
        OffsetDateTime decidedAt,
        String comment,
        List<Long> selectedFindingIds,
        List<Long> riskPatternIds
) {

    public static ReviewDetailResponse of(Review review, Analysis analysis, Product product,
                                          Severity maxSeverity, List<Long> riskPatternIds) {
        return new ReviewDetailResponse(
                review.getId(),
                analysis.getId(),
                product.getId(),
                product.getName(),
                product.getOwnerId(),
                product.getOwner().getName(),
                maxSeverity,
                review.getStatus(),
                review.getSubmissionComment(),
                review.getReviewerId(),
                review.getDecidedAt(),
                review.getComment(),
                review.getSelectedFindingIds().stream().sorted().toList(),
                riskPatternIds.stream().sorted().toList());
    }
}
