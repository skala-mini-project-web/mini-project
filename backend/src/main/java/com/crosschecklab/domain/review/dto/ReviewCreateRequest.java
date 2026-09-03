package com.crosschecklab.domain.review.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// REV-001 요청. 완료된 분석을 검토 대기열에 올린다.
public record ReviewCreateRequest(
        @NotNull Long analysisId,
        @Size(max = 500, message = "검토 요청 의견은 500자를 넘을 수 없습니다.")
        String submissionComment
) {
}
