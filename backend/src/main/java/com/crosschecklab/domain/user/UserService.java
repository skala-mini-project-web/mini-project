package com.crosschecklab.domain.user;

import com.crosschecklab.domain.user.dto.DemoSessionRequest;
import com.crosschecklab.domain.user.dto.DemoSessionResponse;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    // AUTH-001. 요청한 userId/role 이 DB 와 일치하고 활성 상태인지 확인한다.
    // 세션이나 토큰을 만들지 않으며, 이후 요청은 헤더로 매번 다시 식별한다.
    public DemoSessionResponse verifyDemoSession(DemoSessionRequest request) {
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.DEMO_USER_NOT_FOUND));

        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.DEMO_USER_INACTIVE);
        }
        if (user.getRole() != request.role()) {
            throw new BusinessException(ErrorCode.ROLE_MISMATCH);
        }
        return DemoSessionResponse.from(user);
    }
}
