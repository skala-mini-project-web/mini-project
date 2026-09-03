package com.crosschecklab.global.security;

import com.crosschecklab.global.common.enums.UserRole;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import org.springframework.stereotype.Component;

// 역할·소유권 검증 (API 명세 §9 RBAC).
// 엔티티에 의존하지 않도록 소유자 id 만 받는다. 문서는 document → product → owner 로 파생해서 넘긴다.
@Component
public class OwnershipChecker {

    // 쓰기 작업. 소유자 본인만 허용한다.
    public void requireOwner(Long ownerId, DemoUser user) {
        if (!user.id().equals(ownerId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_OWNERSHIP);
        }
    }

    // 조회 작업. 검토자는 담당이 아니어도 볼 수 있다 (Product/Document/Analysis 조회 권한).
    public void requireOwnerOrReviewer(Long ownerId, DemoUser user) {
        if (user.isComplianceReviewer()) {
            return;
        }
        requireOwner(ownerId, user);
    }

    public void requireRole(DemoUser user, UserRole required) {
        if (user.role() != required) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
