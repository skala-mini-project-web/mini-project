package com.crosschecklab.domain.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

// REV-001~003 통합 검증. 분석 실행은 이 테스트 범위가 아니라 COMPLETED 분석을 직접 넣고 시작한다.
class ReviewApiTest extends IntegrationTestSupport {

    // V2 시드: 1 = pm_park(PRODUCT_MANAGER, 아래 상품의 소유자), 2 = reviewer_kim(COMPLIANCE_REVIEWER)
    private static final String USER_ID_HEADER = "X-Demo-User-Id";
    private static final String ROLE_HEADER = "X-Demo-Role";
    private static final long PM_ID = 1L;
    private static final long REVIEWER_ID = 2L;

    @Autowired
    private JdbcTemplate jdbc;

    private Long analysisId;
    private Long highFindingId;
    private Long lowFindingId;

    private MockHttpServletRequestBuilder asPm(MockHttpServletRequestBuilder builder) {
        return builder.header(USER_ID_HEADER, PM_ID).header(ROLE_HEADER, "PRODUCT_MANAGER");
    }

    private MockHttpServletRequestBuilder asReviewer(MockHttpServletRequestBuilder builder) {
        return builder.header(USER_ID_HEADER, REVIEWER_ID).header(ROLE_HEADER, "COMPLIANCE_REVIEWER");
    }

    // 컨테이너는 JVM 당 하나라 여기서 만든 행이 다른 테스트로 새어 나간다. 앞뒤로 비운다.
    @BeforeEach
    void setUp() {
        clearFixtures();
        analysisId = insertCompletedAnalysis(PM_ID, "스마트 인컴 투자상품", "hash-main");
        highFindingId = insertFinding(analysisId, "안정성 표현이 원금보장으로 오인될 가능성이 있습니다.", "HIGH");
        lowFindingId = insertFinding(analysisId, "용어 설명이 부족합니다.", "LOW");
    }

    @AfterEach
    void tearDown() {
        clearFixtures();
    }

    // 참조 순서대로 지운다 (risk_patterns → reviews → findings → analyses → documents → products).
    private void clearFixtures() {
        jdbc.update("DELETE FROM risk_patterns");
        jdbc.update("DELETE FROM reviews");
        jdbc.update("DELETE FROM findings");
        jdbc.update("DELETE FROM analyses");
        jdbc.update("DELETE FROM product_documents");
        jdbc.update("DELETE FROM products");
    }

    private Long insertCompletedAnalysis(long ownerId, String productName, String inputHash) {
        Long productId = jdbc.queryForObject("""
                INSERT INTO products (owner_id, name, product_type, created_at, updated_at)
                VALUES (?, ?, 'INVESTMENT', NOW(), NOW())
                RETURNING id""", Long.class, ownerId, productName);
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
                VALUES (?, 1, 'COMPLETED', 100, 82, TRUE, FALSE, ?, NOW(), NOW(), NOW())
                RETURNING id""", Long.class, documentId, inputHash);
    }

    private Long insertFinding(Long analysisId, String statement, String severity) {
        return jdbc.queryForObject("""
                INSERT INTO findings (analysis_id, statement, severity, recommendation, created_at, updated_at)
                VALUES (?, ?, ?, '표현을 보완하세요.', NOW(), NOW())
                RETURNING id""", Long.class, analysisId, statement, severity);
    }

    private Long createReview(Long analysisId) throws Exception {
        String body = mockMvc.perform(asPm(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("analysisId", analysisId)))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("reviewId").asLong();
    }

    private MockHttpServletRequestBuilder decision(Long reviewId, Map<String, Object> body) throws Exception {
        return post("/api/reviews/{id}/decision", reviewId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    @Test
    @DisplayName("REV-001: 완료 분석을 검토 요청하면 201이고 분석은 IN_REVIEW가 된다")
    void createReviewMovesAnalysisToInReview() throws Exception {
        mockMvc.perform(asPm(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("analysisId", analysisId)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewId").isNumber())
                .andExpect(jsonPath("$.analysisId").value(analysisId))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").exists());

        assertThat(jdbc.queryForObject("SELECT status FROM analyses WHERE id = ?", String.class, analysisId))
                .isEqualTo("IN_REVIEW");
    }

    @Test
    @DisplayName("REV-001: 같은 분석을 다시 제출하면 409 REVIEW_ALREADY_EXISTS")
    void duplicateReviewIsConflict() throws Exception {
        createReview(analysisId);

        mockMvc.perform(asPm(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("analysisId", analysisId)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.REVIEW_ALREADY_EXISTS.name()));
    }

    @Test
    @DisplayName("REV-001: 완료되지 않은 분석은 409 ANALYSIS_NOT_COMPLETED")
    void reviewOnRunningAnalysisIsConflict() throws Exception {
        jdbc.update("UPDATE analyses SET status = 'RUNNING', progress = 50 WHERE id = ?", analysisId);

        mockMvc.perform(asPm(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("analysisId", analysisId)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.ANALYSIS_NOT_COMPLETED.name()));
    }

    @Test
    @DisplayName("REV-001: 타인 분석의 검토 요청은 403")
    void reviewOnOthersAnalysisIsForbidden() throws Exception {
        Long othersAnalysisId = insertCompletedAnalysis(REVIEWER_ID, "타인 상품", "hash-other");

        mockMvc.perform(asPm(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("analysisId", othersAnalysisId)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.FORBIDDEN_OWNERSHIP.name()));
    }

    @Test
    @DisplayName("REV-002: 검토함은 위험도 내림차순 → 제출시간 오름차순으로 정렬된다")
    void queueIsSortedBySeverityThenSubmittedAt() throws Exception {
        // 먼저 제출된 LOW 분석, 나중에 제출된 HIGH 분석 → HIGH 가 앞에 와야 한다.
        Long lowAnalysisId = insertCompletedAnalysis(PM_ID, "저위험 적금", "hash-low");
        insertFinding(lowAnalysisId, "안내 문구가 짧습니다.", "LOW");
        Long lowReviewId = createReview(lowAnalysisId);
        jdbc.update("UPDATE reviews SET created_at = NOW() - INTERVAL '1 hour' WHERE id = ?", lowReviewId);
        Long highReviewId = createReview(analysisId);

        mockMvc.perform(asReviewer(get("/api/reviews").param("status", "PENDING")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].reviewId").value(highReviewId))
                .andExpect(jsonPath("$.items[0].maxSeverity").value("HIGH"))
                .andExpect(jsonPath("$.items[0].productName").value("스마트 인컴 투자상품"))
                .andExpect(jsonPath("$.items[0].ownerName").value("박서준 대리"))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.items[1].reviewId").value(lowReviewId))
                .andExpect(jsonPath("$.items[1].maxSeverity").value("LOW"));
    }

    @Test
    @DisplayName("REV-002: severity 필터는 해당 위험도 Finding 을 가진 검토만 남긴다")
    void queueFiltersBySeverity() throws Exception {
        Long lowAnalysisId = insertCompletedAnalysis(PM_ID, "저위험 적금", "hash-low");
        insertFinding(lowAnalysisId, "안내 문구가 짧습니다.", "LOW");
        createReview(lowAnalysisId);
        Long highReviewId = createReview(analysisId);

        mockMvc.perform(asReviewer(get("/api/reviews").param("severity", "HIGH")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].reviewId").value(highReviewId));
    }

    @Test
    @DisplayName("REV-002: 상품 담당자의 검토함 조회는 403")
    void queueIsReviewerOnly() throws Exception {
        createReview(analysisId);

        mockMvc.perform(asPm(get("/api/reviews")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.FORBIDDEN.name()));
    }

    @Test
    @DisplayName("REV-003: 승인하면 선택한 Finding 만 ACTIVE RiskPattern 으로 승격된다")
    void approvePromotesSelectedFindingsOnly() throws Exception {
        Long reviewId = createReview(analysisId);

        mockMvc.perform(asReviewer(decision(reviewId, Map.of(
                        "status", "APPROVED",
                        "comment", "원금 손실 가능성을 첫 문장에 명시하세요.",
                        "selectedFindingIds", List.of(highFindingId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewId").value(reviewId))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewerId").value(REVIEWER_ID))
                .andExpect(jsonPath("$.riskPatternIds.length()").value(1))
                .andExpect(jsonPath("$.decidedAt").exists());

        // 승격 데이터에서 원본 Finding 과 Review 를 역추적할 수 있어야 한다.
        Map<String, Object> pattern = jdbc.queryForMap(
                "SELECT finding_id, review_id, name, severity, status FROM risk_patterns");
        assertThat(pattern.get("finding_id")).isEqualTo(highFindingId);
        assertThat(pattern.get("review_id")).isEqualTo(reviewId);
        assertThat(pattern.get("severity")).isEqualTo("HIGH");
        assertThat(pattern.get("status")).isEqualTo("ACTIVE");
        assertThat(pattern.get("name")).isEqualTo("안정성 표현이 원금보장으로 오인될 가능성이 있습니다.");

        // 선택하지 않은 LOW Finding 은 승격되지 않는다.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM risk_patterns WHERE finding_id = ?", Integer.class, lowFindingId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM review_selected_findings WHERE review_id = ?", Integer.class, reviewId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("REV-003: 반려는 사유만 남기고 승격하지 않는다")
    void rejectRecordsCommentWithoutPromotion() throws Exception {
        Long reviewId = createReview(analysisId);

        mockMvc.perform(asReviewer(decision(reviewId, Map.of(
                        "status", "REJECTED",
                        "comment", "근거 인용이 부족합니다."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.riskPatternIds.length()").value(0));

        // 담당자 대시보드가 "수정 필요"로 표시할 수 있도록 결정자·사유·시각이 남는다.
        Map<String, Object> review = jdbc.queryForMap(
                "SELECT status, reviewer_id, comment, decided_at FROM reviews WHERE id = ?", reviewId);
        assertThat(review.get("status")).isEqualTo("REJECTED");
        assertThat(review.get("reviewer_id")).isEqualTo(REVIEWER_ID);
        assertThat(review.get("comment")).isEqualTo("근거 인용이 부족합니다.");
        assertThat(review.get("decided_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_patterns", Integer.class)).isZero();
    }

    @Test
    @DisplayName("REV-003: 결정 재처리는 409 REVIEW_ALREADY_DECIDED")
    void secondDecisionIsConflict() throws Exception {
        Long reviewId = createReview(analysisId);
        mockMvc.perform(asReviewer(decision(reviewId, Map.of(
                        "status", "APPROVED",
                        "selectedFindingIds", List.of(highFindingId)))))
                .andExpect(status().isOk());

        mockMvc.perform(asReviewer(decision(reviewId, Map.of(
                        "status", "REJECTED",
                        "comment", "다시 봐야 합니다."))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.REVIEW_ALREADY_DECIDED.name()));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_patterns", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("REV-003: 승인인데 selectedFindingIds 가 없으면 400 INVALID_FINDING_SELECTION")
    void approveWithoutSelectionIsBadRequest() throws Exception {
        Long reviewId = createReview(analysisId);

        mockMvc.perform(asReviewer(decision(reviewId, Map.of("status", "APPROVED"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.INVALID_FINDING_SELECTION.name()));
    }

    @Test
    @DisplayName("REV-003: 다른 분석의 Finding 을 선택하면 400 INVALID_FINDING_SELECTION")
    void approveWithForeignFindingIsBadRequest() throws Exception {
        Long otherAnalysisId = insertCompletedAnalysis(PM_ID, "다른 상품", "hash-foreign");
        Long foreignFindingId = insertFinding(otherAnalysisId, "다른 분석의 지적입니다.", "HIGH");
        Long reviewId = createReview(analysisId);

        mockMvc.perform(asReviewer(decision(reviewId, Map.of(
                        "status", "APPROVED",
                        "selectedFindingIds", List.of(foreignFindingId)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.INVALID_FINDING_SELECTION.name()));
    }

    @Test
    @DisplayName("REV-003: 반려인데 comment 가 없으면 400 COMMENT_REQUIRED")
    void rejectWithoutCommentIsBadRequest() throws Exception {
        Long reviewId = createReview(analysisId);

        mockMvc.perform(asReviewer(decision(reviewId, Map.of("status", "REJECTED"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.COMMENT_REQUIRED.name()));
    }

    @Test
    @DisplayName("REV-003: 상품 담당자의 결정 호출은 403")
    void decisionByProductManagerIsForbidden() throws Exception {
        Long reviewId = createReview(analysisId);

        mockMvc.perform(asPm(decision(reviewId, Map.of(
                        "status", "APPROVED",
                        "selectedFindingIds", List.of(highFindingId)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.FORBIDDEN.name()));
    }

    @Test
    @DisplayName("REV-004: 상품 담당자가 자기 분석의 반려 사유를 조회한다")
    void ownerReadsRejectionReason() throws Exception {
        Long reviewId = createReview(analysisId);
        mockMvc.perform(asReviewer(decision(reviewId, Map.of(
                        "status", "REJECTED",
                        "comment", "원금 손실 가능성을 첫 문장에 명시하세요."))))
                .andExpect(status().isOk());

        mockMvc.perform(asPm(get("/api/analyses/{id}/review", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewId").value(reviewId))
                .andExpect(jsonPath("$.analysisId").value(analysisId))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.comment").value("원금 손실 가능성을 첫 문장에 명시하세요."))
                .andExpect(jsonPath("$.reviewerId").value(REVIEWER_ID))
                .andExpect(jsonPath("$.decidedAt").exists())
                // 반려는 승격 대상이 없으므로 선택 목록이 비어 있다.
                .andExpect(jsonPath("$.selectedFindingIds").isEmpty());
    }

    @Test
    @DisplayName("REV-004: 승인 결과에는 승격된 Finding 이 함께 보인다")
    void ownerReadsApprovedFindings() throws Exception {
        Long reviewId = createReview(analysisId);
        mockMvc.perform(asReviewer(decision(reviewId, Map.of(
                        "status", "APPROVED",
                        "selectedFindingIds", List.of(highFindingId)))))
                .andExpect(status().isOk());

        mockMvc.perform(asPm(get("/api/analyses/{id}/review", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.selectedFindingIds[0]").value(highFindingId));
    }

    @Test
    @DisplayName("REV-004: 결정 전에는 PENDING 이고 결정 필드가 비어 있다")
    void pendingReviewHasNoDecisionFields() throws Exception {
        createReview(analysisId);

        // 키 자체는 내려가고 값만 null 이다 (FE 가 필드 존재를 가정해도 깨지지 않는다).
        mockMvc.perform(asPm(get("/api/analyses/{id}/review", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reviewerId").value(nullValue()))
                .andExpect(jsonPath("$.decidedAt").value(nullValue()))
                .andExpect(jsonPath("$.comment").value(nullValue()))
                .andExpect(jsonPath("$.selectedFindingIds").isEmpty());
    }

    @Test
    @DisplayName("REV-004: 검토자도 조회할 수 있다")
    void reviewerCanRead() throws Exception {
        Long reviewId = createReview(analysisId);

        mockMvc.perform(asReviewer(get("/api/analyses/{id}/review", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewId").value(reviewId));
    }

    @Test
    @DisplayName("REV-004: 검토 요청 전이면 404 REVIEW_NOT_FOUND")
    void missingReviewIsNotFound() throws Exception {
        mockMvc.perform(asPm(get("/api/analyses/{id}/review", analysisId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.REVIEW_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("REV-004: 남의 분석은 검토 존재 여부와 무관하게 403")
    void otherOwnersAnalysisIsForbidden() throws Exception {
        // 소유자가 검토자(2)인 상품이라 담당자(1)에게는 남의 분석이다.
        Long othersAnalysisId = insertCompletedAnalysis(REVIEWER_ID, "타인 상품", "hash-other");

        mockMvc.perform(asPm(get("/api/analyses/{id}/review", othersAnalysisId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.FORBIDDEN_OWNERSHIP.name()));
    }
}
