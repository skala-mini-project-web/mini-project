package com.crosschecklab.global.security;

import static com.crosschecklab.global.security.DemoAuthenticationFilter.ROLE_HEADER;
import static com.crosschecklab.global.security.DemoAuthenticationFilter.USER_ID_HEADER;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosschecklab.support.IntegrationTestSupport;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@DisplayName("데모 인증 필터와 소유권 검증")
class DemoAuthenticationTest extends IntegrationTestSupport {

    // V2 시드: 1 = pm_park(PRODUCT_MANAGER), 2 = reviewer_kim(COMPLIANCE_REVIEWER)
    private static final String PM_ID = "1";
    private static final String REVIEWER_ID = "2";

    @Test
    @DisplayName("헤더가 유효하면 DB 에서 조회한 사용자가 주입된다")
    void injectsAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/test/current-user")
                        .header(USER_ID_HEADER, PM_ID)
                        .header(ROLE_HEADER, "PRODUCT_MANAGER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.username").value("pm_park"))
                .andExpect(jsonPath("$.role").value("PRODUCT_MANAGER"));
    }

    @Test
    @DisplayName("헤더가 없으면 401 DEMO_AUTHENTICATION_REQUIRED")
    void rejectsMissingHeaders() throws Exception {
        mockMvc.perform(get("/test/current-user"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("DEMO_AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("X-Demo-User-Id 만 있고 role 헤더가 없으면 401")
    void rejectsPartialHeaders() throws Exception {
        mockMvc.perform(get("/test/current-user").header(USER_ID_HEADER, PM_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("DEMO_AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("헤더의 role 은 신뢰하지 않고 DB 값과 다르면 401 ROLE_MISMATCH")
    void rejectsRoleMismatch() throws Exception {
        mockMvc.perform(get("/test/current-user")
                        .header(USER_ID_HEADER, PM_ID)
                        .header(ROLE_HEADER, "COMPLIANCE_REVIEWER"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ROLE_MISMATCH"));
    }

    @Test
    @DisplayName("존재하지 않는 사용자면 401 DEMO_USER_NOT_FOUND")
    void rejectsUnknownUser() throws Exception {
        mockMvc.perform(get("/test/current-user")
                        .header(USER_ID_HEADER, "9999")
                        .header(ROLE_HEADER, "PRODUCT_MANAGER"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("DEMO_USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("숫자가 아닌 사용자 ID 헤더는 401")
    void rejectsNonNumericUserId() throws Exception {
        mockMvc.perform(get("/test/current-user")
                        .header(USER_ID_HEADER, "pm_park")
                        .header(ROLE_HEADER, "PRODUCT_MANAGER"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("DEMO_AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("소유자 본인이면 통과한다")
    void allowsOwner() throws Exception {
        mockMvc.perform(get("/test/owned-by/{ownerId}", 1)
                        .header(USER_ID_HEADER, PM_ID)
                        .header(ROLE_HEADER, "PRODUCT_MANAGER"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("타인 소유 리소스에 쓰기 접근하면 403 FORBIDDEN_OWNERSHIP")
    void rejectsNonOwner() throws Exception {
        mockMvc.perform(get("/test/owned-by/{ownerId}", 999)
                        .header(USER_ID_HEADER, PM_ID)
                        .header(ROLE_HEADER, "PRODUCT_MANAGER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_OWNERSHIP"));
    }

    @Test
    @DisplayName("검토자는 담당이 아닌 리소스도 조회할 수 있다")
    void allowsReviewerToRead() throws Exception {
        mockMvc.perform(get("/test/readable-by/{ownerId}", 999)
                        .header(USER_ID_HEADER, REVIEWER_ID)
                        .header(ROLE_HEADER, "COMPLIANCE_REVIEWER"))
                .andExpect(status().isOk());
    }

    // @CurrentUser 와 OwnershipChecker 를 검증하기 위한 테스트 전용 엔드포인트.
    // 실제 도메인 컨트롤러는 이후 브랜치에서 추가된다.
    @TestConfiguration
    static class TestEndpoints {

        @RestController
        @RequiredArgsConstructor
        static class ProbeController {

            private final OwnershipChecker ownershipChecker;

            @GetMapping("/test/current-user")
            DemoUser currentUser(@CurrentUser DemoUser user) {
                return user;
            }

            @GetMapping("/test/owned-by/{ownerId}")
            String requireOwner(@PathVariable Long ownerId, @CurrentUser DemoUser user) {
                ownershipChecker.requireOwner(ownerId, user);
                return "ok";
            }

            @GetMapping("/test/readable-by/{ownerId}")
            String requireOwnerOrReviewer(@PathVariable Long ownerId, @CurrentUser DemoUser user) {
                ownershipChecker.requireOwnerOrReviewer(ownerId, user);
                return "ok";
            }
        }
    }
}
