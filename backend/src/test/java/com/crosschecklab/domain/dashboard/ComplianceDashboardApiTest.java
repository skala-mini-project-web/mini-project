package com.crosschecklab.domain.dashboard;

import static com.crosschecklab.global.security.DemoAuthenticationFilter.ROLE_HEADER;
import static com.crosschecklab.global.security.DemoAuthenticationFilter.USER_ID_HEADER;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosschecklab.support.IntegrationTestSupport;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// V2 시드: 1 = pm_park(PRODUCT_MANAGER), 2 = reviewer_kim(COMPLIANCE_REVIEWER)
// 집계 대상 행이 시드에 없으므로 각 테스트가 직접 만든다.
@DisplayName("DASH-002 검토자 대시보드 API")
class ComplianceDashboardApiTest extends IntegrationTestSupport {

    private static final long PM = 1L;
    private static final long REVIEWER = 2L;

    @Autowired
    private JdbcTemplate jdbc;

    // 컨테이너는 JVM 당 하나라 여기서 만든 행이 다른 테스트로 새어 나간다.
    // 집계값을 단언하려면 앞뒤로 비워야 한다. 참조 순서대로 지운다.
    @BeforeEach
    @AfterEach
    void clearFixtures() {
        jdbc.update("DELETE FROM guardfit_actions");
        jdbc.update("DELETE FROM risk_patterns");
        jdbc.update("DELETE FROM reviews");
        jdbc.update("DELETE FROM findings");
        jdbc.update("DELETE FROM analyses");
        jdbc.update("DELETE FROM product_documents");
        jdbc.update("DELETE FROM products");
    }

    private MockHttpServletRequestBuilder asPm(MockHttpServletRequestBuilder builder) {
        return builder.header(USER_ID_HEADER, PM).header(ROLE_HEADER, "PRODUCT_MANAGER");
    }

    private MockHttpServletRequestBuilder asReviewer(MockHttpServletRequestBuilder builder) {
        return builder.header(USER_ID_HEADER, REVIEWER).header(ROLE_HEADER, "COMPLIANCE_REVIEWER");
    }

    private long insertProduct(String name) {
        return jdbc.queryForObject("""
                INSERT INTO products (owner_id, name, product_type, created_at, updated_at)
                VALUES (?, ?, 'INVESTMENT', NOW(), NOW())
                RETURNING id""", Long.class, PM, name);
    }

    private long insertDocument(long productId) {
        return jdbc.queryForObject("""
                INSERT INTO product_documents
                    (product_id, file_name, media_type, storage_key, extract_status, extracted_text,
                     confirmed, created_at, updated_at)
                VALUES (?, '상품설명서.pdf', 'application/pdf', 'mock://documents/clean',
                        'READY', '확정된 추출 텍스트입니다.', TRUE, NOW(), NOW())
                RETURNING id""", Long.class, productId);
    }

    // red_team_pack_id 1 은 V2 시드 값이다.
    private long insertAnalysis(long documentId) {
        return jdbc.queryForObject("""
                INSERT INTO analyses
                    (product_document_id, red_team_pack_id, status, progress, input_hash, created_at, updated_at)
                VALUES (?, 1, 'IN_REVIEW', 100, ?, NOW(), NOW())
                RETURNING id""", Long.class, documentId, "hash-" + documentId);
    }

    private long insertFinding(long analysisId, String severity) {
        return jdbc.queryForObject("""
                INSERT INTO findings (analysis_id, statement, severity, created_at, updated_at)
                VALUES (?, '안정성 표현이 원금보장으로 오인될 수 있습니다.', ?, NOW(), NOW())
                RETURNING id""", Long.class, analysisId, severity);
    }

    // decidedDaysAgo 가 null 이면 PENDING, 아니면 그날 결정된 검토다.
    private long insertReview(long analysisId, String status, Integer decidedDaysAgo) {
        return jdbc.queryForObject("""
                INSERT INTO reviews (analysis_id, reviewer_id, status, decided_at, created_at, updated_at)
                VALUES (?, ?, ?,
                        CASE WHEN CAST(? AS int) IS NULL THEN NULL
                             ELSE NOW() - CAST(? AS int) * INTERVAL '1 day' END,
                        NOW(), NOW())
                RETURNING id""",
                Long.class, analysisId,
                decidedDaysAgo == null ? null : REVIEWER, status, decidedDaysAgo, decidedDaysAgo);
    }

    private void insertRiskPattern(long findingId, long reviewId, String patternStatus) {
        jdbc.update("""
                INSERT INTO risk_patterns (finding_id, review_id, name, severity, status, created_at, updated_at)
                VALUES (?, ?, '원금보장 오해', 'HIGH', ?, NOW(), NOW())""", findingId, reviewId, patternStatus);
    }

    // 상품 → 문서 → 분석 → Finding → 검토를 한 줄로 붙이고 검토 id 를 돌려준다.
    private long insertReviewChain(String productName, String reviewStatus, Integer decidedDaysAgo,
                                   String... findingSeverities) {
        long analysisId = insertAnalysis(insertDocument(insertProduct(productName)));
        for (String severity : findingSeverities) {
            insertFinding(analysisId, severity);
        }
        return insertReview(analysisId, reviewStatus, decidedDaysAgo);
    }

    @Test
    @DisplayName("검토 대기·HIGH Finding·활성 패턴·기간 내 결정 건수를 함께 돌려준다")
    void summarizesReviewWorkload() throws Exception {
        insertReviewChain("대기 상품 A", "PENDING", null, "HIGH", "HIGH", "LOW");
        insertReviewChain("대기 상품 B", "PENDING", null, "HIGH");
        long decidedReviewId = insertReviewChain("승인 상품", "APPROVED", 0, "MEDIUM");
        insertRiskPattern(insertFinding(
                jdbc.queryForObject("SELECT analysis_id FROM reviews WHERE id = ?", Long.class, decidedReviewId),
                "HIGH"), decidedReviewId, "ACTIVE");

        mockMvc.perform(asReviewer(get("/api/dashboard/compliance")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.pendingReviews").value(2))
                .andExpect(jsonPath("$.summary.highFindings").value(3))
                .andExpect(jsonPath("$.summary.activeRiskPatterns").value(1))
                .andExpect(jsonPath("$.summary.decidedInRange").value(1));
    }

    @Test
    @DisplayName("결정된 검토의 HIGH Finding 과 DRAFT 패턴은 남은 일감으로 세지 않는다")
    void excludesSettledWork() throws Exception {
        long decidedReviewId = insertReviewChain("승인 상품", "APPROVED", 0, "HIGH");
        long findingId = jdbc.queryForObject(
                "SELECT id FROM findings WHERE analysis_id = (SELECT analysis_id FROM reviews WHERE id = ?)",
                Long.class, decidedReviewId);
        insertRiskPattern(findingId, decidedReviewId, "DRAFT");

        mockMvc.perform(asReviewer(get("/api/dashboard/compliance")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.pendingReviews").value(0))
                .andExpect(jsonPath("$.summary.highFindings").value(0))
                .andExpect(jsonPath("$.summary.activeRiskPatterns").value(0));
    }

    @Test
    @DisplayName("우선 검토 목록은 위험도 높은 순 → 제출 빠른 순으로 PENDING 만 담는다")
    void ordersPriorityReviewsBySeverityThenSubmittedAt() throws Exception {
        insertReviewChain("보통 상품", "PENDING", null, "MEDIUM");
        insertReviewChain("위험 상품", "PENDING", null, "HIGH");
        insertReviewChain("결정된 상품", "PENDING", null, "HIGH");
        insertReviewChain("반려 상품", "REJECTED", 0, "HIGH");

        mockMvc.perform(asReviewer(get("/api/dashboard/compliance")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priorityReviews.length()").value(3))
                .andExpect(jsonPath("$.priorityReviews[0].productName").value("위험 상품"))
                .andExpect(jsonPath("$.priorityReviews[0].maxSeverity").value("HIGH"))
                .andExpect(jsonPath("$.priorityReviews[0].status").value("PENDING"))
                .andExpect(jsonPath("$.priorityReviews[0].ownerName").value("박서준 대리"))
                .andExpect(jsonPath("$.priorityReviews[1].productName").value("결정된 상품"))
                .andExpect(jsonPath("$.priorityReviews[2].productName").value("보통 상품"));
    }

    @Test
    @DisplayName("우선 검토 목록은 대기가 많아도 상위 5건까지만 내려준다")
    void capsPriorityReviews() throws Exception {
        for (int i = 0; i < 7; i++) {
            insertReviewChain("대기 상품 " + i, "PENDING", null, "HIGH");
        }

        mockMvc.perform(asReviewer(get("/api/dashboard/compliance")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.pendingReviews").value(7))
                .andExpect(jsonPath("$.priorityReviews.length()").value(5));
    }

    @Test
    @DisplayName("from·to 기간 안에서 결정된 검토만 센다")
    void countsDecisionsInsideRange() throws Exception {
        insertReviewChain("어제 결정", "APPROVED", 1, "HIGH");
        insertReviewChain("오늘 결정", "REJECTED", 0, "HIGH");

        LocalDate today = LocalDate.now();

        mockMvc.perform(asReviewer(get("/api/dashboard/compliance")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.decidedInRange").value(1));

        // to 는 양끝 포함이라 오늘까지 지정하면 오늘 결정도 함께 잡힌다.
        mockMvc.perform(asReviewer(get("/api/dashboard/compliance")
                        .param("from", today.minusDays(1).toString())
                        .param("to", today.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.decidedInRange").value(2));

        mockMvc.perform(asReviewer(get("/api/dashboard/compliance")
                        .param("from", today.minusDays(1).toString())
                        .param("to", today.minusDays(1).toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.decidedInRange").value(1));
    }

    @Test
    @DisplayName("시작일이 종료일보다 늦으면 400 INVALID_DATE_RANGE")
    void rejectsInvertedDateRange() throws Exception {
        mockMvc.perform(asReviewer(get("/api/dashboard/compliance")
                        .param("from", "2026-09-03")
                        .param("to", "2026-09-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_DATE_RANGE"));
    }

    @Test
    @DisplayName("날짜 형식이 깨졌으면 400 VALIDATION_ERROR")
    void rejectsMalformedDate() throws Exception {
        mockMvc.perform(asReviewer(get("/api/dashboard/compliance").param("from", "2026-13-99")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("from"));
    }

    @Test
    @DisplayName("집계할 것이 없으면 카드는 전부 0 이고 목록은 비어 있다")
    void returnsZerosWhenNothingToCount() throws Exception {
        mockMvc.perform(asReviewer(get("/api/dashboard/compliance")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.pendingReviews").value(0))
                .andExpect(jsonPath("$.summary.highFindings").value(0))
                .andExpect(jsonPath("$.summary.activeRiskPatterns").value(0))
                .andExpect(jsonPath("$.summary.decidedInRange").value(0))
                .andExpect(jsonPath("$.priorityReviews").isEmpty());
    }

    @Test
    @DisplayName("인증된 대시보드 응답은 캐시에 저장되지 않는다")
    void doesNotAllowCaching() throws Exception {
        // Spring Security 기본 헤더 정책이 전역으로 no-store 를 붙인다.
        // 정책을 끄면 검토 업무 집계가 브라우저·중간 캐시에 남으므로 여기서 고정한다.
        mockMvc.perform(asReviewer(get("/api/dashboard/compliance")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    @DisplayName("상품 담당자가 호출하면 403")
    void rejectsProductManager() throws Exception {
        mockMvc.perform(asPm(get("/api/dashboard/compliance")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("데모 헤더가 없으면 401")
    void requiresDemoHeaders() throws Exception {
        mockMvc.perform(get("/api/dashboard/compliance"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("DEMO_AUTHENTICATION_REQUIRED"));
    }
}
