package com.crosschecklab.domain.reference;

import static com.crosschecklab.global.security.DemoAuthenticationFilter.ROLE_HEADER;
import static com.crosschecklab.global.security.DemoAuthenticationFilter.USER_ID_HEADER;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosschecklab.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// V2 시드 데이터를 기준으로 검증한다.
// 시드가 바뀌면 이 테스트가 먼저 깨지도록 개수와 id 를 명시적으로 단언한다.
@DisplayName("기준 데이터 조회 API")
class ReferenceDataApiTest extends IntegrationTestSupport {

    private static final String PM_ID = "1";
    private static final String PM_ROLE = "PRODUCT_MANAGER";

    private MockHttpServletRequestBuilder authorized(String url, Object... vars) {
        return get(url, vars)
                .header(USER_ID_HEADER, PM_ID)
                .header(ROLE_HEADER, PM_ROLE);
    }

    @Nested
    @DisplayName("EVD-001 GET /api/evidence-documents")
    class EvidenceDocuments {

        @Test
        @DisplayName("필터가 없으면 시드된 근거 문서 3건을 id 순서로 반환한다")
        void returnsAllSeededDocuments() throws Exception {
            mockMvc.perform(authorized("/api/evidence-documents"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(3))
                    .andExpect(jsonPath("$.items[0].evidenceDocumentId").value(1))
                    .andExpect(jsonPath("$.items[0].sourceType").value("INTERNAL_POLICY"))
                    .andExpect(jsonPath("$.items[0].version").value("DEMO-2026.1"))
                    .andExpect(jsonPath("$.items[0].content").isNotEmpty())
                    .andExpect(jsonPath("$.items[0].active").value(true))
                    .andExpect(jsonPath("$.items[1].evidenceDocumentId").value(2))
                    .andExpect(jsonPath("$.items[2].evidenceDocumentId").value(3));
        }

        @Test
        @DisplayName("sourceType 으로 필터링된다")
        void filtersBySourceType() throws Exception {
            mockMvc.perform(authorized("/api/evidence-documents").param("sourceType", "REGULATION"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].evidenceDocumentId").value(2))
                    .andExpect(jsonPath("$.items[0].sourceType").value("REGULATION"));
        }

        @Test
        @DisplayName("active 필터도 함께 적용된다")
        void filtersByActive() throws Exception {
            mockMvc.perform(authorized("/api/evidence-documents").param("active", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(3));

            mockMvc.perform(authorized("/api/evidence-documents").param("active", "false"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty());
        }

        @Test
        @DisplayName("정의되지 않은 sourceType 은 400 VALIDATION_ERROR")
        void rejectsUnknownSourceType() throws Exception {
            mockMvc.perform(authorized("/api/evidence-documents").param("sourceType", "UNKNOWN"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("데모 헤더가 없으면 401")
        void requiresDemoHeaders() throws Exception {
            mockMvc.perform(get("/api/evidence-documents"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("DEMO_AUTHENTICATION_REQUIRED"));
        }
    }

    @Nested
    @DisplayName("TEST-001 GET /api/persona-templates")
    class PersonaTemplates {

        @Test
        @DisplayName("Persona 5종을 시드 id 순서로 반환하고 jsonb 필드가 구조 그대로 내려간다")
        void returnsFiveSeededPersonas() throws Exception {
            mockMvc.perform(authorized("/api/persona-templates"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(5))
                    .andExpect(jsonPath("$.items[0].personaTemplateId").value(1))
                    .andExpect(jsonPath("$.items[0].code").value("FINANCIAL_BEGINNER"))
                    .andExpect(jsonPath("$.items[0].name").value("금융 초보자"))
                    .andExpect(jsonPath("$.items[0].criteria.financialLiteracy").value("LOW"))
                    .andExpect(jsonPath("$.items[0].riskFocus").isArray())
                    .andExpect(jsonPath("$.items[0].riskFocus[0]").value("확정수익 오해"))
                    .andExpect(jsonPath("$.items[4].personaTemplateId").value(5))
                    .andExpect(jsonPath("$.items[4].code").value("SELF_EMPLOYED"));
        }

        @Test
        @DisplayName("active 필터가 적용된다")
        void filtersByActive() throws Exception {
            mockMvc.perform(authorized("/api/persona-templates").param("active", "false"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty());
        }

        @Test
        @DisplayName("데모 헤더가 없으면 401")
        void requiresDemoHeaders() throws Exception {
            mockMvc.perform(get("/api/persona-templates"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("TEST-002 GET /api/red-team-packs")
    class RedTeamPacks {

        @Test
        @DisplayName("Pack 1종과 소속 규칙 6종을 sortOrder 순서로 반환한다")
        void returnsPackWithRules() throws Exception {
            mockMvc.perform(authorized("/api/red-team-packs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].redTeamPackId").value(1))
                    .andExpect(jsonPath("$.items[0].code").value("CORE_FINANCIAL_RISK_V1"))
                    .andExpect(jsonPath("$.items[0].active").value(true))
                    .andExpect(jsonPath("$.items[0].rules.length()").value(6))
                    .andExpect(jsonPath("$.items[0].rules[0].code").value("RETURN_FRAMING"))
                    .andExpect(jsonPath("$.items[0].rules[0].sortOrder").value(1))
                    .andExpect(jsonPath("$.items[0].rules[5].code").value("COGNITIVE_ACCESSIBILITY"));
        }

        @Test
        @DisplayName("ruleCodes 는 rules 와 같은 순서의 코드 배열이다")
        void flattensRuleCodes() throws Exception {
            mockMvc.perform(authorized("/api/red-team-packs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].ruleCodes.length()").value(6))
                    .andExpect(jsonPath("$.items[0].ruleCodes[0]").value("RETURN_FRAMING"))
                    .andExpect(jsonPath("$.items[0].ruleCodes[2]").value("COST_OMISSION"))
                    .andExpect(jsonPath("$.items[0].ruleCodes[5]").value("COGNITIVE_ACCESSIBILITY"));
        }

        @Test
        @DisplayName("검토자도 동일하게 조회할 수 있다")
        void allowsReviewer() throws Exception {
            mockMvc.perform(get("/api/red-team-packs")
                            .header(USER_ID_HEADER, "2")
                            .header(ROLE_HEADER, "COMPLIANCE_REVIEWER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1));
        }

        @Test
        @DisplayName("데모 헤더가 없으면 401")
        void requiresDemoHeaders() throws Exception {
            mockMvc.perform(get("/api/red-team-packs"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
