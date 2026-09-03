package com.crosschecklab.domain.user.dto;

import com.crosschecklab.global.common.enums.UserRole;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record DemoSessionRequest(
        @NotNull(message = "필수 값입니다.")
        @Positive(message = "1 이상의 사용자 ID 여야 합니다.")
        Long userId,

        @NotNull(message = "필수 값입니다.")
        UserRole role
) {
}
