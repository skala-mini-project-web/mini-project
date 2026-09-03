package com.crosschecklab.domain.analysis.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

// ANA-001 요청. 개수·활성 여부 검증은 AnalysisInputLoader 가 DB 를 보고 판정한다.
public record AnalysisCreateRequest(
        @NotNull Long productDocumentId,
        @NotEmpty List<Long> evidenceDocumentIds,
        @NotEmpty List<Long> personaIds,
        @NotNull Long redTeamPackId
) {
}
