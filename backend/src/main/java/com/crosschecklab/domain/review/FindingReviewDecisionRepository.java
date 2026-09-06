package com.crosschecklab.domain.review;

import com.crosschecklab.global.common.enums.ReviewStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingReviewDecisionRepository
        extends JpaRepository<FindingReviewDecision, Long> {

    List<FindingReviewDecision> findAllByReviewIdAndDecisionOrderByFindingRevisionIdAsc(
            Long reviewId, ReviewStatus decision);
}
