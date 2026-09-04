package com.crosschecklab.domain.dashboard;

import static com.crosschecklab.global.security.DemoAuthenticationFilter.ROLE_HEADER;
import static com.crosschecklab.global.security.DemoAuthenticationFilter.USER_ID_HEADER;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosschecklab.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// V2 시드: 1 = pm_park(PRODUCT_MANAGER), 2 = reviewer_kim(COMPLIANCE_REVIEWER)
// 집계 대상 행이 시드에 없으므로 각 테스트가 직접 만든다.
@DisplayName("DASH-001 담당자 대시보드 API")
class DashboardApiTest extends IntegrationTestSupport {

    private static final long PM = 1L;
    private static final long REVIEWER = 2L;

    @Autowired
    private JdbcTemplate jdbc;

    // 컨테이너는 JVM 당 하나라 여기서 만든 행이 다른 테스트로 새어 나간다.
    // 집계값을 단언하려면 앞뒤로 비워야 한다. 참조 순서대로 지운다.
    @BeforeEach
    @AfterEach
    void clearFixtures() {
        jdbc.update("DELETE FROM reviews");
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

    private long insertProduct(long ownerId) {
        return jdbc.queryForObject("""
                INSERT INTO products (owner_id, name, product_type, created_at, updated_at)
                VALUES (?, '집계용 상품', 'INVESTMENT', NOW(), NOW())
                RETURNING id""", Long.class, ownerId);
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
    private long insertAnalysis(long documentId, String status) {
        return jdbc.queryForObject("""
                INSERT INTO analyses
                    (product_document_id, red_team_pack_id, status, progress, input_hash, created_at, updated_at)
                VALUES (?, 1, ?, 0, ?, NOW(), NOW())
                RETURNING id""", Long.class, documentId, status, "hash-" + documentId + "-" + status);
    }

    private void insertReview(long analysisId, String status) {
        jdbc.update("""
                INSERT INTO reviews (analysis_id, status, created_at, updated_at)
                VALUES (?, ?, NOW(), NOW())""", analysisId, status);
    }

    // 상품 하나에 문서·분석·검토를 한 줄로 붙인다.
    private void insertProductWithReview(long ownerId, String analysisStatus, String reviewStatus) {
        long analysisId = insertAnalysis(insertDocument(insertProduct(ownerId)), analysisStatus);
        if (reviewStatus != null) {
            insertReview(analysisId, reviewStatus);
        }
    }

    @Test
    @DisplayName("내 상품 수와 분석 중·검토 대기·승인 완료 건수를 함께 돌려준다")
    void summarizesOwnWork() throws Exception {
        insertProductWithReview(PM, "RUNNING", null);
        insertProductWithReview(PM, "COMPLETED", "PENDING");
        insertProductWithReview(PM, "COMPLETED", "APPROVED");
        insertProduct(PM); // 문서도 분석도 없는 상품. 상품 수에만 잡힌다.

        mockMvc.perform(asPm(get("/api/dashboard/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myProducts").value(4))
                .andExpect(jsonPath("$.analyzing").value(1))
                .andExpect(jsonPath("$.pendingReview").value(1))
                .andExpect(jsonPath("$.approved").value(1));
    }

    @Test
    @DisplayName("아직 실행되지 않은 CREATED 분석도 분석 중으로 센다")
    void countsCreatedAsAnalyzing() throws Exception {
        insertProductWithReview(PM, "CREATED", null);

        mockMvc.perform(asPm(get("/api/dashboard/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analyzing").value(1));
    }

    @Test
    @DisplayName("반려된 검토는 검토 대기에도 승인 완료에도 잡히지 않는다")
    void excludesRejectedReview() throws Exception {
        insertProductWithReview(PM, "COMPLETED", "REJECTED");

        mockMvc.perform(asPm(get("/api/dashboard/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myProducts").value(1))
                .andExpect(jsonPath("$.pendingReview").value(0))
                .andExpect(jsonPath("$.approved").value(0));
    }

    @Test
    @DisplayName("타인 상품은 집계에 섞이지 않는다")
    void excludesOtherOwners() throws Exception {
        insertProductWithReview(REVIEWER, "RUNNING", null);
        insertProductWithReview(REVIEWER, "COMPLETED", "PENDING");
        insertProductWithReview(PM, "COMPLETED", "APPROVED");

        mockMvc.perform(asPm(get("/api/dashboard/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myProducts").value(1))
                .andExpect(jsonPath("$.analyzing").value(0))
                .andExpect(jsonPath("$.pendingReview").value(0))
                .andExpect(jsonPath("$.approved").value(1));
    }

    @Test
    @DisplayName("집계할 것이 없으면 전부 0 이다")
    void returnsZerosWhenNothingToCount() throws Exception {
        mockMvc.perform(asPm(get("/api/dashboard/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myProducts").value(0))
                .andExpect(jsonPath("$.analyzing").value(0))
                .andExpect(jsonPath("$.pendingReview").value(0))
                .andExpect(jsonPath("$.approved").value(0));
    }

    @Test
    @DisplayName("검토자가 호출해도 403 이 아니라 본인 소유 기준으로 집계한다")
    void allowsReviewerWithOwnScope() throws Exception {
        insertProductWithReview(PM, "RUNNING", null);
        insertProduct(REVIEWER);

        mockMvc.perform(asReviewer(get("/api/dashboard/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myProducts").value(1))
                .andExpect(jsonPath("$.analyzing").value(0));
    }

    @Test
    @DisplayName("데모 헤더가 없으면 401")
    void requiresDemoHeaders() throws Exception {
        mockMvc.perform(get("/api/dashboard/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("DEMO_AUTHENTICATION_REQUIRED"));
    }
}
