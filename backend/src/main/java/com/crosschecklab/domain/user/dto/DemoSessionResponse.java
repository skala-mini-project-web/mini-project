package com.crosschecklab.domain.user.dto;

import com.crosschecklab.domain.user.User;
import com.crosschecklab.global.common.enums.UserRole;

public record DemoSessionResponse(
        Long userId,
        String username,
        String name,
        UserRole role,
        boolean active
) {

    public static DemoSessionResponse from(User user) {
        return new DemoSessionResponse(
                user.getId(), user.getUsername(), user.getName(), user.getRole(), user.isActive());
    }
}
