package com.crosschecklab.domain.review.dto;

import com.crosschecklab.global.common.enums.ReviewStatus;
import jakarta.validation.constraints.NotNull;
import java.util.List;

// REV-003 요청. status 는 APPROVED 또는 REJECTED 만 허용한다(PENDING 은 결정이 아니다).
// selectedFindingIds 는 APPROVED 일 때만 의미가 있고, comment 는 REJECTED 일 때 필수다.
// 조합 검증은 ReviewService 가 도메인 에러코드(INVALID_FINDING_SELECTION / COMMENT_REQUIRED)로 처리한다.
public record ReviewDecisionRequest(
        @NotNull ReviewStatus status,
        String comment,
        List<Long> selectedFindingIds
) {

    public List<Long> selectedFindingIdsOrEmpty() {
        return selectedFindingIds == null ? List.of() : selectedFindingIds;
    }
}
