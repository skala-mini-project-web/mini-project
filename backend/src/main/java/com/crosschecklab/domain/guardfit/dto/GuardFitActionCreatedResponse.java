package com.crosschecklab.domain.guardfit.dto;

import com.crosschecklab.domain.guardfit.GuardFitAction;
import com.crosschecklab.global.common.enums.GuardFitStatus;
import java.time.OffsetDateTime;

// GF-001 응답 (201)
public record GuardFitActionCreatedResponse(
        Long actionId,
        GuardFitStatus status,
        OffsetDateTime createdAt
) {

    public static GuardFitActionCreatedResponse from(GuardFitAction action) {
        return new GuardFitActionCreatedResponse(action.getId(), action.getStatus(), action.getCreatedAt());
    }
}
