package com.crosschecklab.domain.user.dto;

import com.crosschecklab.domain.user.User;
import com.crosschecklab.global.common.enums.UserRole;

public record DemoUserResponse(
        Long userId,
        String name,
        UserRole role
) {

    public static DemoUserResponse from(User user) {
        return new DemoUserResponse(user.getId(), user.getName(), user.getRole());
    }
}
