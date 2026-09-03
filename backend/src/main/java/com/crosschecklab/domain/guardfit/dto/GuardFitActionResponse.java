package com.crosschecklab.domain.guardfit.dto;

import com.crosschecklab.domain.guardfit.GuardFitAction;
import com.crosschecklab.global.common.enums.GuardFitActionType;
import com.crosschecklab.global.common.enums.GuardFitStatus;
import java.time.OffsetDateTime;

// GF-002 목록 한 건이자 GF-003 편집·승인 응답.
// riskPatternId 를 함께 내려 화면에서 패턴 → Finding → Review 로 역추적할 수 있게 한다.
public record GuardFitActionResponse(
        Long actionId,
        Long riskPatternId,
        GuardFitActionType actionType,
        String label,
        String placement,
        boolean required,
        String preview,
        GuardFitStatus status,
        OffsetDateTime updatedAt
) {

    public static GuardFitActionResponse from(GuardFitAction action) {
        return new GuardFitActionResponse(
                action.getId(),
                action.getRiskPatternId(),
                action.getActionType(),
                action.getLabel(),
                action.getPlacement(),
                action.isRequired(),
                action.getPreview(),
                action.getStatus(),
                action.getUpdatedAt());
    }
}
