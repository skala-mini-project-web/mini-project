package com.crosschecklab.domain.user;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosschecklab.support.IntegrationTestSupport;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("데모 인증 API")
class AuthApiTest extends IntegrationTestSupport {

    // V2 시드: 1 = pm_park(PRODUCT_MANAGER), 2 = reviewer_kim(COMPLIANCE_REVIEWER)
    private static final long PRODUCT_MANAGER_ID = 1L;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void restoreActiveDemoUsers() {
        jdbc.update("UPDATE users SET active = TRUE WHERE id IN (1, 2)");
    }

    @Test
    @DisplayName("활성 사용자를 ID 순으로 필요한 공개 필드만 반환한다")
    void returnsActiveDemoUsersInIdOrderWithoutPrivateFields() throws Exception {
        mockMvc.perform(get("/api/demo/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].userId").value(1))
                .andExpect(jsonPath("$[0].name").value("박서준 대리"))
                .andExpect(jsonPath("$[0].role").value("PRODUCT_MANAGER"))
                .andExpect(jsonPath("$[0].*", hasSize(3)))
                .andExpect(jsonPath("$[1].userId").value(2))
                .andExpect(jsonPath("$[1].name").value("김민지 과장"))
                .andExpect(jsonPath("$[1].role").value("COMPLIANCE_REVIEWER"))
                .andExpect(jsonPath("$[1].*", hasSize(3)));
    }

    @Test
    @DisplayName("비활성 사용자는 목록에서 제외하고 기존 세션 검증에서도 거부한다")
    void excludesInactiveDemoUsers() throws Exception {
        jdbc.update("UPDATE users SET active = FALSE WHERE id = 1");

        mockMvc.perform(get("/api/demo/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].userId").value(2));

        mockMvc.perform(request(Map.of("userId", PRODUCT_MANAGER_ID, "role", "PRODUCT_MANAGER")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("DEMO_USER_INACTIVE"));
    }

    @Test
    @DisplayName("활성 사용자가 없으면 빈 목록을 반환한다")
    void returnsEmptyListWhenNoActiveDemoUsersExist() throws Exception {
        jdbc.update("UPDATE users SET active = FALSE WHERE id IN (1, 2)");

        mockMvc.perform(get("/api/demo/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("userId 와 role 이 DB 와 일치하면 사용자 정보를 반환한다")
    void returnsUserWhenRoleMatches() throws Exception {
        mockMvc.perform(request(Map.of("userId", PRODUCT_MANAGER_ID, "role", "PRODUCT_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.username").value("pm_park"))
                .andExpect(jsonPath("$.role").value("PRODUCT_MANAGER"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("요청한 role 이 저장된 role 과 다르면 401 ROLE_MISMATCH")
    void rejectsRoleMismatch() throws Exception {
        mockMvc.perform(request(Map.of("userId", PRODUCT_MANAGER_ID, "role", "COMPLIANCE_REVIEWER")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ROLE_MISMATCH"));
    }

    @Test
    @DisplayName("존재하지 않는 사용자면 401 DEMO_USER_NOT_FOUND")
    void rejectsUnknownUser() throws Exception {
        mockMvc.perform(request(Map.of("userId", 9999, "role", "PRODUCT_MANAGER")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("DEMO_USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("필수 값이 없으면 400 VALIDATION_ERROR 와 fieldErrors 를 반환한다")
    void rejectsMissingFields() throws Exception {
        mockMvc.perform(request(Map.of("role", "PRODUCT_MANAGER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("userId"));
    }

    @Test
    @DisplayName("정의되지 않은 role 문자열이면 400")
    void rejectsUnknownRole() throws Exception {
        mockMvc.perform(request(Map.of("userId", PRODUCT_MANAGER_ID, "role", "ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(Map<String, Object> body)
            throws Exception {
        return post("/api/demo/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }
}
