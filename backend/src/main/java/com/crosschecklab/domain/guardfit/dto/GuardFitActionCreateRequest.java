package com.crosschecklab.domain.guardfit.dto;

import com.crosschecklab.global.common.enums.GuardFitActionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// GF-001 요청. actionType 은 LABEL/WARNING/QUESTION/COMPARISON 4종만 허용한다(API 명세 §10 확정 사항).
// label · placement 는 VARCHAR(255) 라 길이를 여기서 걸러 400 으로 끊는다.
// status 는 받지 않는다. 후보는 항상 DRAFT 로 생성되고 승인은 GF-003 에서만 일어난다.
public record GuardFitActionCreateRequest(
        @NotNull Long riskPatternId,
        @NotNull GuardFitActionType actionType,
        @NotBlank @Size(max = 255) String label,
        @NotBlank @Size(max = 255) String placement,
        Boolean required,
        String preview
) {

    // 미지정은 DB 기본값과 같은 false 로 본다.
    public boolean requiredOrDefault() {
        return Boolean.TRUE.equals(required);
    }
}
