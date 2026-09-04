package com.crosschecklab.domain.guardfit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosschecklab.global.error.ErrorCode;
import com.crosschecklab.support.IntegrationTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// GF-001~003 통합 검증. 승격 흐름(REV-003)은 Review·Risk 테스트 범위라
// 여기서는 ACTIVE/DRAFT 패턴을 직접 넣고 보호조치부터 시작한다.
class GuardFitActionApiTest extends IntegrationTestSupport {

    // V2 시드: 1 = pm_park(PRODUCT_MANAGER), 2 = reviewer_kim(COMPLIANCE_REVIEWER)
    private static final String USER_ID_HEADER = "X-Demo-User-Id";
    private static final String ROLE_HEADER = "X-Demo-Role";
    private static final long PM_ID = 1L;
    private static final long REVIEWER_ID = 2L;

    // V2 시드: 1 = CORE_FINANCIAL_RISK_V1
    private static final long CORE_PACK_ID = 1L;

    @Autowired
    private JdbcTemplate jdbc;

    private Long activePatternId;
    private Long otherActivePatternId;
    private Long draftPatternId;

    private MockHttpServletRequestBuilder asPm(MockHttpServletRequestBuilder builder) {
        return builder.header(USER_ID_HEADER, PM_ID).header(ROLE_HEADER, "PRODUCT_MANAGER");
    }

    private MockHttpServletRequestBuilder asReviewer(MockHttpServletRequestBuilder builder) {
        return builder.header(USER_ID_HEADER, REVIEWER_ID).header(ROLE_HEADER, "COMPLIANCE_REVIEWER");
    }

    private MockHttpServletRequestBuilder traced(MockHttpServletRequestBuilder builder, String traceId) {
        return builder.header("X-Trace-Id", traceId);
    }

    // 컨테이너는 JVM 당 하나라 여기서 만든 변경 가능한 행이 다른 테스트로 새어 나간다.
    // append-only audit_events 는 비우지 않고 trace/resource 조건으로 검증 범위를 격리한다.
    @BeforeEach
    void setUp() {
        clearFixtures();

        Long analysisId = insertCompletedAnalysis();
        Long reviewId = insertApprovedReview(analysisId);
        activePatternId = insertPattern(
                insertFinding(analysisId, "안정성 표현이 원금보장으로 오인될 가능성이 있습니다.", "HIGH"),
                reviewId, "원금보장 오해", "HIGH", "ACTIVE");
        otherActivePatternId = insertPattern(
                insertFinding(analysisId, "수수료 안내가 분산되어 있습니다.", "MEDIUM"),
                reviewId, "비용 누락", "MEDIUM", "ACTIVE");
        draftPatternId = insertPattern(
                insertFinding(analysisId, "용어 설명이 부족합니다.", "LOW"),
                reviewId, "용어 설명 부족", "LOW", "DRAFT");
    }

    @AfterEach
    void tearDown() {
        clearFixtures();
    }

    // 참조 순서대로 지운다 (guardfit_actions → risk_patterns → reviews → findings → analyses → documents → products).
    private void clearFixtures() {
        jdbc.update("DELETE FROM guardfit_actions");
        jdbc.update("DELETE FROM risk_patterns");
        jdbc.update("DELETE FROM reviews");
        jdbc.update("DELETE FROM findings");
        jdbc.update("DELETE FROM analyses");
        jdbc.update("DELETE FROM product_documents");
        jdbc.update("DELETE FROM products");
    }

    private Long insertCompletedAnalysis() {
        Long productId = jdbc.queryForObject("""
                INSERT INTO products (owner_id, name, product_type, created_at, updated_at)
                VALUES (?, '스마트 인컴 투자상품', 'INVESTMENT', NOW(), NOW())
                RETURNING id""", Long.class, PM_ID);
        Long documentId = jdbc.queryForObject("""
                INSERT INTO product_documents
                    (product_id, file_name, media_type, storage_key, extract_status, extracted_text,
                     confirmed, created_at, updated_at)
                VALUES (?, '상품설명서.pdf', 'application/pdf', 'mock://documents/guarantee',
                        'READY', '최근 안정적인 수익률을 기록한 투자상품입니다.', TRUE, NOW(), NOW())
                RETURNING id""", Long.class, productId);
        return jdbc.queryForObject("""
                INSERT INTO analyses
                    (product_document_id, red_team_pack_id, status, progress, risk_score,
                     requires_human_approval, retryable, input_hash, completed_at, created_at, updated_at)
                VALUES (?, ?, 'IN_REVIEW', 100, 82, TRUE, FALSE, 'hash-guardfit', NOW(), NOW(), NOW())
                RETURNING id""", Long.class, documentId, CORE_PACK_ID);
    }

    private Long insertFinding(Long analysisId, String statement, String severity) {
        return jdbc.queryForObject("""
                INSERT INTO findings (analysis_id, statement, severity, recommendation, created_at, updated_at)
                VALUES (?, ?, ?, '표현을 보완하세요.', NOW(), NOW())
                RETURNING id""", Long.class, analysisId, statement, severity);
    }

    private Long insertApprovedReview(Long analysisId) {
        return jdbc.queryForObject("""
                INSERT INTO reviews (analysis_id, reviewer_id, status, comment, decided_at, created_at, updated_at)
                VALUES (?, ?, 'APPROVED', '표현을 보완하세요.', NOW(), NOW(), NOW())
                RETURNING id""", Long.class, analysisId, REVIEWER_ID);
    }

    private Long insertPattern(Long findingId, Long reviewId, String name, String severity, String status) {
        return jdbc.queryForObject("""
                INSERT INTO risk_patterns (finding_id, review_id, name, severity, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW())
                RETURNING id""", Long.class, findingId, reviewId, name, severity, status);
    }

    private Long insertAction(Long riskPatternId, String actionType, String label, String status) {
        return jdbc.queryForObject("""
                INSERT INTO guardfit_actions
                    (risk_pattern_id, action_type, label, placement, required, preview, status,
                     updated_by, created_at, updated_at)
                VALUES (?, ?, ?, '상품 상세 상단', TRUE, '본 상품은 원금 손실이 발생할 수 있습니다.', ?,
                        ?, NOW(), NOW())
                RETURNING id""", Long.class, riskPatternId, actionType, label, status, REVIEWER_ID);
    }

    private String createBody(Long riskPatternId) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "riskPatternId", riskPatternId,
                "actionType", "WARNING",
                "label", "원금 손실 가능",
                "placement", "상품 상세 상단",
                "required", true,
                "preview", "본 상품은 원금 손실이 발생할 수 있습니다."));
    }

    private String updateBody(String status) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "actionType", "WARNING",
                "label", "투자위험 안내",
                "placement", "상품 상세 상단",
                "required", true,
                "preview", "본 상품은 원금 손실이 발생할 수 있습니다.",
                "status", status));
    }

    private void assertAudit(String traceId, String action, Long resourceId, String label) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT trace_id, actor_id, action, resource_type, resource_id, resource_label, analysis_id
                FROM audit_events
                WHERE trace_id = ?
                  AND resource_type = 'GUARDFIT_ACTION'
                  AND resource_id = ?
                """, traceId, resourceId);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("trace_id")).isEqualTo(traceId);
            assertThat(row.get("actor_id")).isEqualTo(REVIEWER_ID);
            assertThat(row.get("action")).isEqualTo(action);
            assertThat(row.get("resource_type")).isEqualTo("GUARDFIT_ACTION");
            assertThat(row.get("resource_id")).isEqualTo(resourceId);
            assertThat(row.get("resource_label")).isEqualTo(label);
            assertThat(row.get("analysis_id")).isNull();
        });
    }

    private void assertNoAudit(String traceId) {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE trace_id = ?", Long.class, traceId)).isZero();
    }

    @Test
    @DisplayName("GF-001: 검토자는 ACTIVE 패턴에 보호조치 후보를 DRAFT 로 생성한다")
    void reviewerCreatesDraftAction() throws Exception {
        String traceId = "guardfit-create-success";
        String response = mockMvc.perform(traced(asReviewer(post("/api/guardfit/actions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(activePatternId)), traceId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actionId").isNumber())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andReturn().getResponse().getContentAsString();

        Long actionId = objectMapper.readTree(response).get("actionId").asLong();
        assertAudit(traceId, "GUARDFIT_ACTION_CREATED", actionId, "원금 손실 가능");
    }

    @Test
    @DisplayName("GF-001: DRAFT 패턴에 보호조치를 붙이면 409 RISK_PATTERN_NOT_ACTIVE")
    void createOnDraftPatternIsConflict() throws Exception {
        String traceId = "guardfit-create-inactive";
        mockMvc.perform(traced(asReviewer(post("/api/guardfit/actions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(draftPatternId)), traceId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.RISK_PATTERN_NOT_ACTIVE.name()));
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("GF-001: 존재하지 않는 패턴이면 404")
    void createOnUnknownPatternIsNotFound() throws Exception {
        String traceId = "guardfit-create-not-found";
        mockMvc.perform(traced(asReviewer(post("/api/guardfit/actions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(999_999L)), traceId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.NOT_FOUND.name()));
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("GF-001: 상품 담당자가 후보를 생성하면 403")
    void productManagerCannotCreate() throws Exception {
        String traceId = "guardfit-create-wrong-role";
        mockMvc.perform(traced(asPm(post("/api/guardfit/actions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(activePatternId)), traceId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.FORBIDDEN.name()));
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("GF-001: 정의되지 않은 actionType 은 400")
    void unknownActionTypeIsBadRequest() throws Exception {
        String traceId = "guardfit-create-invalid-type";
        String body = objectMapper.writeValueAsString(Map.of(
                "riskPatternId", activePatternId,
                "actionType", "WARNING_LABEL",
                "label", "원금 손실 가능",
                "placement", "상품 상세 상단"));

        mockMvc.perform(traced(asReviewer(post("/api/guardfit/actions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body), traceId))
                .andExpect(status().isBadRequest());
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("GF-002: 검토자는 DRAFT·APPROVED 를 모두 조회한다")
    void reviewerSeesAllStatuses() throws Exception {
        insertAction(activePatternId, "WARNING", "원금 손실 가능", "DRAFT");
        insertAction(activePatternId, "LABEL", "투자위험 안내", "APPROVED");

        mockMvc.perform(asReviewer(get("/api/guardfit/actions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items[0].riskPatternId").value(activePatternId))
                .andExpect(jsonPath("$.items[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.items[0].actionType").value("WARNING"))
                .andExpect(jsonPath("$.items[0].label").value("원금 손실 가능"))
                .andExpect(jsonPath("$.items[0].placement").value("상품 상세 상단"))
                .andExpect(jsonPath("$.items[0].required").value(true))
                .andExpect(jsonPath("$.items[0].preview").value("본 상품은 원금 손실이 발생할 수 있습니다."))
                .andExpect(jsonPath("$.items[1].status").value("APPROVED"));
    }

    @Test
    @DisplayName("GF-002: 검토자의 status 필터는 그 상태만 남긴다")
    void reviewerFiltersByStatus() throws Exception {
        insertAction(activePatternId, "WARNING", "원금 손실 가능", "DRAFT");
        Long approvedId = insertAction(activePatternId, "LABEL", "투자위험 안내", "APPROVED");

        mockMvc.perform(asReviewer(get("/api/guardfit/actions").param("status", "APPROVED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].actionId").value(approvedId));
    }

    @Test
    @DisplayName("GF-002: 상품 담당자는 status=DRAFT 를 요청해도 승인본만 본다")
    void productManagerSeesApprovedOnly() throws Exception {
        insertAction(activePatternId, "WARNING", "원금 손실 가능", "DRAFT");
        Long approvedId = insertAction(activePatternId, "LABEL", "투자위험 안내", "APPROVED");

        mockMvc.perform(asPm(get("/api/guardfit/actions").param("status", "DRAFT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].actionId").value(approvedId))
                .andExpect(jsonPath("$.items[0].status").value("APPROVED"));
    }

    @Test
    @DisplayName("GF-002: riskPatternId 필터는 그 패턴의 보호조치만 남긴다")
    void listFiltersByRiskPattern() throws Exception {
        insertAction(activePatternId, "WARNING", "원금 손실 가능", "APPROVED");
        Long otherId = insertAction(otherActivePatternId, "LABEL", "수수료 안내", "APPROVED");

        mockMvc.perform(asReviewer(get("/api/guardfit/actions")
                        .param("riskPatternId", String.valueOf(otherActivePatternId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].actionId").value(otherId));
    }

    @Test
    @DisplayName("GF-002: 페이징은 필터 조건 전체를 기준으로 집계한다")
    void listPaginates() throws Exception {
        insertAction(activePatternId, "WARNING", "원금 손실 가능", "APPROVED");
        insertAction(activePatternId, "LABEL", "투자위험 안내", "APPROVED");
        Long thirdId = insertAction(otherActivePatternId, "QUESTION", "이해도 확인", "APPROVED");

        mockMvc.perform(asReviewer(get("/api/guardfit/actions").param("page", "1").param("size", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].actionId").value(thirdId));
    }

    @Test
    @DisplayName("GF-002: 인증 헤더가 없으면 401")
    void anonymousCannotList() throws Exception {
        mockMvc.perform(get("/api/guardfit/actions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GF-003: 검토자는 문구를 편집하면서 APPROVED 로 승인한다")
    void reviewerEditsAndApproves() throws Exception {
        Long actionId = insertAction(activePatternId, "LABEL", "원금 손실 가능", "DRAFT");
        String traceId = "guardfit-approve-success";

        mockMvc.perform(traced(asReviewer(put("/api/guardfit/actions/{actionId}", actionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("APPROVED")), traceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionId").value(actionId))
                .andExpect(jsonPath("$.riskPatternId").value(activePatternId))
                .andExpect(jsonPath("$.actionType").value("WARNING"))
                .andExpect(jsonPath("$.label").value("투자위험 안내"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.updatedAt").exists());
        assertAudit(traceId, "GUARDFIT_ACTION_APPROVED", actionId, "투자위험 안내");
    }

    @Test
    @DisplayName("GF-003: DRAFT 로 저장하면 승인 전 편집만 반영된다")
    void reviewerEditsWithoutApproving() throws Exception {
        Long actionId = insertAction(activePatternId, "LABEL", "원금 손실 가능", "DRAFT");
        String traceId = "guardfit-update-success";

        mockMvc.perform(traced(asReviewer(put("/api/guardfit/actions/{actionId}", actionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("DRAFT")), traceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("투자위험 안내"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
        assertAudit(traceId, "GUARDFIT_ACTION_UPDATED", actionId, "투자위험 안내");
    }

    @Test
    @DisplayName("GF-003: APPROVED 이후 수정하면 409 ACTION_ALREADY_FINALIZED")
    void approvedActionCannotBeEdited() throws Exception {
        Long actionId = insertAction(activePatternId, "WARNING", "원금 손실 가능", "APPROVED");
        String traceId = "guardfit-update-finalized";

        mockMvc.perform(traced(asReviewer(put("/api/guardfit/actions/{actionId}", actionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("APPROVED")), traceId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.ACTION_ALREADY_FINALIZED.name()));
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("GF-003: 상품 담당자가 편집·승인하면 403")
    void productManagerCannotUpdate() throws Exception {
        Long actionId = insertAction(activePatternId, "WARNING", "원금 손실 가능", "DRAFT");
        String traceId = "guardfit-update-wrong-role";

        mockMvc.perform(traced(asPm(put("/api/guardfit/actions/{actionId}", actionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("APPROVED")), traceId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.FORBIDDEN.name()));
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("GF-003: 존재하지 않는 보호조치는 404")
    void updateUnknownActionIsNotFound() throws Exception {
        String traceId = "guardfit-update-not-found";
        mockMvc.perform(traced(asReviewer(put("/api/guardfit/actions/{actionId}", 999_999L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("APPROVED")), traceId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.NOT_FOUND.name()));
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("GF-003: label 이 비면 400")
    void blankLabelIsBadRequest() throws Exception {
        Long actionId = insertAction(activePatternId, "WARNING", "원금 손실 가능", "DRAFT");
        String traceId = "guardfit-update-blank-label";
        String body = objectMapper.writeValueAsString(Map.of(
                "actionType", "WARNING",
                "label", "  ",
                "placement", "상품 상세 상단",
                "required", true,
                "status", "APPROVED"));

        mockMvc.perform(traced(asReviewer(put("/api/guardfit/actions/{actionId}", actionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body), traceId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.VALIDATION_ERROR.name()));
        assertNoAudit(traceId);
    }
}
