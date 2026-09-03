package com.crosschecklab.global.security;

import com.crosschecklab.domain.user.User;
import com.crosschecklab.global.common.enums.UserRole;

// 인증된 요청의 주체. DB 에서 조회한 값만 담기며 요청 헤더 값을 그대로 싣지 않는다.
public record DemoUser(Long id, String username, String name, UserRole role) {

    public static DemoUser from(User user) {
        return new DemoUser(user.getId(), user.getUsername(), user.getName(), user.getRole());
    }

    public boolean isProductManager() {
        return role == UserRole.PRODUCT_MANAGER;
    }

    public boolean isComplianceReviewer() {
        return role == UserRole.COMPLIANCE_REVIEWER;
    }
}
