package com.crosschecklab.domain.guardfit.dto;

import com.crosschecklab.global.common.enums.GuardFitActionType;
import com.crosschecklab.global.common.enums.GuardFitStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// GF-003 요청. 편집과 승인이 한 번에 일어나므로 PUT 은 전체 교체다.
// status 는 DRAFT(계속 다듬기) 또는 APPROVED(확정) 두 값뿐이다.
// riskPatternId 는 받지 않는다. 다른 패턴에 붙이려면 새 후보를 만든다.
public record GuardFitActionUpdateRequest(
        @NotNull GuardFitActionType actionType,
        @NotBlank @Size(max = 255) String label,
        @NotBlank @Size(max = 255) String placement,
        Boolean required,
        String preview,
        @NotNull GuardFitStatus status
) {

    public boolean requiredOrDefault() {
        return Boolean.TRUE.equals(required);
    }
}
