package com.crosschecklab.global.security;

import com.crosschecklab.domain.user.User;
import com.crosschecklab.domain.user.UserRepository;
import com.crosschecklab.global.common.enums.UserRole;
import com.crosschecklab.global.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

// MVP 데모 인증. X-Demo-User-Id 로 users 를 조회해 SecurityContext 를 채운다.
//
// 헤더의 X-Demo-Role 은 신뢰하지 않는다 (API 명세 §1.1).
// DB 에 저장된 role/active 를 다시 검증하고, 인증 주체에는 DB 값만 싣는다.
//
// 검증에 실패해도 여기서 응답을 쓰지 않고 요청 속성에 실패 사유만 남긴다.
// 인증이 필요한 엔드포인트에서 CurrentUserArgumentResolver 가 이를 BusinessException 으로 바꿔
// GlobalExceptionHandler 의 ErrorResponse 형식으로 나가게 하기 위함이다.
// (필터에서 직접 JSON 을 쓰면 오류 응답 형식이 두 벌이 된다)
@RequiredArgsConstructor
public class DemoAuthenticationFilter extends OncePerRequestFilter {

    public static final String USER_ID_HEADER = "X-Demo-User-Id";
    public static final String ROLE_HEADER = "X-Demo-Role";

    // 인증 실패 사유를 담는 요청 속성 키
    static final String FAILURE_ATTRIBUTE = DemoAuthenticationFilter.class.getName() + ".FAILURE";

    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String userIdHeader = request.getHeader(USER_ID_HEADER);
        String roleHeader = request.getHeader(ROLE_HEADER);

        // 두 헤더가 모두 없으면 익명 요청. 인증이 필요 없는 엔드포인트(예: AUTH-001)는 그대로 통과한다.
        if (!StringUtils.hasText(userIdHeader) && !StringUtils.hasText(roleHeader)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            authenticate(request, userIdHeader, roleHeader);
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(HttpServletRequest request, String userIdHeader, String roleHeader) {
        if (!StringUtils.hasText(userIdHeader) || !StringUtils.hasText(roleHeader)) {
            fail(request, ErrorCode.DEMO_AUTHENTICATION_REQUIRED);
            return;
        }

        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            fail(request, ErrorCode.DEMO_AUTHENTICATION_REQUIRED);
            return;
        }

        Optional<UserRole> requestedRole = parseRole(roleHeader);
        if (requestedRole.isEmpty()) {
            fail(request, ErrorCode.ROLE_MISMATCH);
            return;
        }

        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            fail(request, ErrorCode.DEMO_USER_NOT_FOUND);
            return;
        }

        User user = found.get();
        if (!user.isActive()) {
            fail(request, ErrorCode.DEMO_USER_INACTIVE);
            return;
        }
        if (user.getRole() != requestedRole.get()) {
            fail(request, ErrorCode.ROLE_MISMATCH);
            return;
        }

        DemoUser principal = DemoUser.from(user);
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(user.getRole().authority())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private Long parseUserId(String value) {
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Optional<UserRole> parseRole(String value) {
        try {
            return Optional.of(UserRole.valueOf(value.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private void fail(HttpServletRequest request, ErrorCode errorCode) {
        request.setAttribute(FAILURE_ATTRIBUTE, errorCode);
    }
}
