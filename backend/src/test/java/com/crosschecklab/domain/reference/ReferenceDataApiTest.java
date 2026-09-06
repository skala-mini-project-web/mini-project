package com.crosschecklab.domain.reference;

import static com.crosschecklab.global.security.DemoAuthenticationFilter.ROLE_HEADER;
import static com.crosschecklab.global.security.DemoAuthenticationFilter.USER_ID_HEADER;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosschecklab.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// V10 canonical synthetic corpus와 V20 상황 Persona 시드 데이터를 기준으로 검증한다.
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
                    .andExpect(jsonPath("$.items[0].title").value("중요정보 표시 내부정책 (완전 합성 데모)"))
                    .andExpect(jsonPath("$.items[0].version").value("2026.1"))
                    .andExpect(jsonPath("$.items[0].content")
                            .value(containsString(
                                    "ARGUS | SYNTHETIC DEMO CORPUS IMPORTANT-INFO-DISPLAY-POLICY-v2026.1")))
                    .andExpect(jsonPath("$.items[0].content").value(containsString("완전 합성 데모 문서")))
                    .andExpect(jsonPath("$.items[0].active").value(true))
                    .andExpect(jsonPath("$.items[1].evidenceDocumentId").value(2))
                    .andExpect(jsonPath("$.items[1].sourceType").value("REGULATION"))
                    .andExpect(jsonPath("$.items[1].title")
                            .value("합성 금융소비자 설명의무 규정 발췌 (완전 합성 데모)"))
                    .andExpect(jsonPath("$.items[1].version").value("1.0"))
                    .andExpect(jsonPath("$.items[1].content")
                            .value(containsString(
                                    "ARGUS | SYNTHETIC DEMO CORPUS FINANCIAL-CONSUMER-EXPLANATION-DUTY-EXCERPT-v1")))
                    .andExpect(jsonPath("$.items[1].content").value(containsString("완전 합성 데모 문서")))
                    .andExpect(jsonPath("$.items[2].evidenceDocumentId").value(3))
                    .andExpect(jsonPath("$.items[2].sourceType").value("PRODUCT_POLICY"))
                    .andExpect(jsonPath("$.items[2].title").value("SMART 인컴 상품 운영정책 (완전 합성 데모)"))
                    .andExpect(jsonPath("$.items[2].version").value("1.0"))
                    .andExpect(jsonPath("$.items[2].content")
                            .value(containsString(
                                    "ARGUS | SYNTHETIC DEMO CORPUS SMART-INCOME-PRODUCT-POLICY-v1")))
                    .andExpect(jsonPath("$.items[2].content").value(containsString("완전 합성 데모 문서")));
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
        @DisplayName("활성 상황 Persona 7종을 시드 id 순서로 반환하고 jsonb 필드가 구조 그대로 내려간다")
        void returnsSevenActiveSituationPersonas() throws Exception {
            mockMvc.perform(authorized("/api/persona-templates").param("active", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(7))
                    .andExpect(jsonPath("$.items[0].personaTemplateId").value(6))
                    .andExpect(jsonPath("$.items[0].code").value("LIMITED_PRODUCT_FAMILIARITY"))
                    .andExpect(jsonPath("$.items[0].name").value("상품 이해 지원 필요 상황"))
                    .andExpect(jsonPath("$.items[0].criteria.testSituation")
                            .value("상품 구조·용어 이해 지원이 필요한 상황"))
                    .andExpect(jsonPath("$.items[0].riskFocus").isArray())
                    .andExpect(jsonPath("$.items[0].riskFocus[0]").value("상품 구조 설명"))
                    .andExpect(jsonPath("$.items[0].active").value(true))
                    .andExpect(jsonPath("$.items[1].code").value("LOSS_RECOVERY_PRESSURE"))
                    .andExpect(jsonPath("$.items[1].active").value(true))
                    .andExpect(jsonPath("$.items[2].code").value("NEAR_TERM_LIQUIDITY_NEED"))
                    .andExpect(jsonPath("$.items[2].active").value(true))
                    .andExpect(jsonPath("$.items[3].code")
                            .value("VARIABLE_CASH_FLOW_OR_REPAYMENT_CONSTRAINT"))
                    .andExpect(jsonPath("$.items[3].active").value(true))
                    .andExpect(jsonPath("$.items[4].code").value("EXPLANATION_ACCESS_SUPPORT"))
                    .andExpect(jsonPath("$.items[4].active").value(true))
                    .andExpect(jsonPath("$.items[5].code").value("DIGITAL_CHANNEL_SUPPORT"))
                    .andExpect(jsonPath("$.items[5].active").value(true))
                    .andExpect(jsonPath("$.items[6].personaTemplateId").value(12))
                    .andExpect(jsonPath("$.items[6].code").value("LIFE_EVENT_FINANCIAL_STRESS"))
                    .andExpect(jsonPath("$.items[6].active").value(true));
        }

        @Test
        @DisplayName("비활성 필터는 보존된 legacy Persona 5종만 반환한다")
        void returnsOnlyInactiveLegacyPersonas() throws Exception {
            mockMvc.perform(authorized("/api/persona-templates").param("active", "false"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(5))
                    .andExpect(jsonPath("$.items[0].personaTemplateId").value(1))
                    .andExpect(jsonPath("$.items[0].code").value("FINANCIAL_BEGINNER"))
                    .andExpect(jsonPath("$.items[0].active").value(false))
                    .andExpect(jsonPath("$.items[1].code").value("SENIOR"))
                    .andExpect(jsonPath("$.items[1].active").value(false))
                    .andExpect(jsonPath("$.items[2].code").value("LOSS_EXPERIENCED"))
                    .andExpect(jsonPath("$.items[2].active").value(false))
                    .andExpect(jsonPath("$.items[3].code").value("SHORT_TERM_LIQUIDITY"))
                    .andExpect(jsonPath("$.items[3].active").value(false))
                    .andExpect(jsonPath("$.items[4].personaTemplateId").value(5))
                    .andExpect(jsonPath("$.items[4].code").value("SELF_EMPLOYED"))
                    .andExpect(jsonPath("$.items[4].active").value(false));
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
