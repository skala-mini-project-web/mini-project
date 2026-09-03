package com.crosschecklab.domain.review.dto;

import jakarta.validation.constraints.NotNull;

// REV-001 요청. 완료된 분석을 검토 대기열에 올린다.
public record ReviewCreateRequest(@NotNull Long analysisId) {
}
