package com.crosschecklab.domain.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosschecklab.domain.analysis.Finding;
import com.crosschecklab.domain.analysis.FindingRepository;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import com.crosschecklab.support.IntegrationTestSupport;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// RISK-001 통합 검증. 검토 결정 흐름은 REV-003 테스트 범위라 여기서는 승격된 패턴을 직접 넣고 시작한다.
class RiskPatternApiTest extends IntegrationTestSupport {

    // V2 시드: 1 = pm_park(PRODUCT_MANAGER), 2 = reviewer_kim(COMPLIANCE_REVIEWER)
    private static final String USER_ID_HEADER = "X-Demo-User-Id";
    private static final String ROLE_HEADER = "X-Demo-Role";
    private static final long PM_ID = 1L;
    private static final long REVIEWER_ID = 2L;
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    // V2 시드: 1 = CORE_FINANCIAL_RISK_V1 (규칙 6종 전부 보유)
    private static final long CORE_PACK_ID = 1L;
    // 규칙을 하나도 갖지 않는 Pack. ruleCode 필터가 실제로 걸러내는지 확인하려고 테스트에서만 만든다.
    private static final long EMPTY_PACK_ID = 99L;

    private static final long PERSONA_FINANCIAL_BEGINNER = 1L;
    private static final long PERSONA_SENIOR = 2L;
    private static final long PERSONA_LOSS_EXPERIENCED = 3L;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RiskPatternService riskPatternService;

    @Autowired
    private FindingRepository findingRepository;

    private Long highFindingId;
    private Long lowFindingId;
    private Long mediumFindingId;
    private Long reviewId;
    private Long highPatternId;
    private Long lowPatternId;
    private Long unrelatedPatternId;

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

        Long coreAnalysisId = insertCompletedAnalysis("스마트 인컴 투자상품", CORE_PACK_ID, "hash-core");
        highFindingId = insertFinding(coreAnalysisId, "안정성 표현이 원금보장으로 오인될 가능성이 있습니다.", "HIGH",
                PERSONA_FINANCIAL_BEGINNER, PERSONA_SENIOR);
        lowFindingId = insertFinding(coreAnalysisId, "용어 설명이 부족합니다.", "LOW", PERSONA_LOSS_EXPERIENCED);
        reviewId = insertApprovedReview(coreAnalysisId);
        highPatternId = insertPattern(highFindingId, reviewId, "원금보장 오해", "HIGH", "ACTIVE");
        lowPatternId = insertPattern(lowFindingId, reviewId, "용어 설명 부족", "LOW", "ACTIVE");

        // 규칙이 없는 Pack 으로 분석된 패턴. ruleCode 필터에서 빠져야 한다.
        jdbc.update("""
                INSERT INTO red_team_packs (id, code, name, active, created_at, updated_at)
                VALUES (?, 'TEST_EMPTY_PACK', '규칙 없는 테스트 Pack', TRUE, NOW(), NOW())""", EMPTY_PACK_ID);
        Long emptyPackAnalysisId = insertCompletedAnalysis("퇴직연금 안심플랜", EMPTY_PACK_ID, "hash-empty");
        mediumFindingId = insertFinding(emptyPackAnalysisId, "수수료 안내가 분산되어 있습니다.", "MEDIUM",
                PERSONA_FINANCIAL_BEGINNER);
        unrelatedPatternId = insertPattern(
                mediumFindingId, insertApprovedReview(emptyPackAnalysisId), "비용 누락", "MEDIUM", "ACTIVE");
    }

    @AfterEach
    void tearDown() {
        clearFixtures();
    }

    // 변경 가능한 fixture 만 참조 순서대로 지운다 (risk_patterns → reviews → findings → analyses → documents → products → 테스트 Pack).
    private void clearFixtures() {
        jdbc.update("DELETE FROM risk_patterns");
        jdbc.update("DELETE FROM reviews");
        jdbc.update("DELETE FROM findings");
        jdbc.update("DELETE FROM analyses");
        jdbc.update("DELETE FROM product_documents");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM red_team_packs WHERE id = ?", EMPTY_PACK_ID);
    }

    private Long insertCompletedAnalysis(String productName, long redTeamPackId, String inputHash) {
        Long productId = jdbc.queryForObject("""
                INSERT INTO products (owner_id, name, product_type, created_at, updated_at)
                VALUES (?, ?, 'INVESTMENT', NOW(), NOW())
                RETURNING id""", Long.class, PM_ID, productName);
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
                VALUES (?, ?, 'IN_REVIEW', 100, 82, TRUE, FALSE, ?, NOW(), NOW(), NOW())
                RETURNING id""", Long.class, documentId, redTeamPackId, inputHash);
    }

    private Long insertFinding(Long analysisId, String statement, String severity, Long... personaTemplateIds) {
        Long findingId = jdbc.queryForObject("""
                INSERT INTO findings (analysis_id, statement, severity, recommendation, created_at, updated_at)
                VALUES (?, ?, ?, '표현을 보완하세요.', NOW(), NOW())
                RETURNING id""", Long.class, analysisId, statement, severity);
        for (Long personaTemplateId : personaTemplateIds) {
            jdbc.update("INSERT INTO finding_affected_personas (finding_id, persona_template_id) VALUES (?, ?)",
                    findingId, personaTemplateId);
        }
        return findingId;
    }

    private Long insertApprovedReview(Long analysisId, Long... selectedFindingIds) {
        Long insertedReviewId = jdbc.queryForObject("""
                INSERT INTO reviews (analysis_id, reviewer_id, status, comment, decided_at, created_at, updated_at)
                VALUES (?, ?, 'APPROVED', '표현을 보완하세요.', NOW(), NOW(), NOW())
                RETURNING id""", Long.class, analysisId, REVIEWER_ID);
        for (Long selectedFindingId : selectedFindingIds) {
            jdbc.update("""
                    INSERT INTO review_selected_findings (review_id, finding_id)
                    VALUES (?, ?)""", insertedReviewId, selectedFindingId);
        }
        return insertedReviewId;
    }

    private Long insertReview(Long analysisId, String status) {
        if ("PENDING".equals(status)) {
            return jdbc.queryForObject("""
                    INSERT INTO reviews (analysis_id, status, created_at, updated_at)
                    VALUES (?, 'PENDING', NOW(), NOW())
                    RETURNING id""", Long.class, analysisId);
        }
        return jdbc.queryForObject("""
                INSERT INTO reviews (analysis_id, reviewer_id, status, comment, decided_at, created_at, updated_at)
                VALUES (?, ?, ?, '반려 사유입니다.', NOW(), NOW(), NOW())
                RETURNING id""", Long.class, analysisId, REVIEWER_ID, status);
    }

    private Long insertPattern(Long findingId, Long reviewId, String name, String severity, String status) {
        return jdbc.queryForObject("""
                INSERT INTO risk_patterns (finding_id, review_id, name, severity, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW())
                RETURNING id""", Long.class, findingId, reviewId, name, severity, status);
    }

    private void assertAuditEvent(String traceId, String action, Long resourceId, String resourceLabel) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT action, resource_type, resource_id, actor_id, analysis_id, trace_id, resource_label
                FROM audit_events
                WHERE trace_id = ? AND resource_type = 'RISK_PATTERN' AND resource_id = ?""",
                traceId, resourceId);
        assertThat(rows).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("action", action)
                .containsEntry("resource_type", "RISK_PATTERN")
                .containsEntry("resource_id", resourceId)
                .containsEntry("actor_id", REVIEWER_ID)
                .containsEntry("analysis_id", null)
                .containsEntry("trace_id", traceId)
                .containsEntry("resource_label", resourceLabel));
    }

    private void assertNoAuditEvent(String traceId, Long targetResourceId) {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE trace_id = ? AND resource_id = ?",
                Integer.class, traceId, targetResourceId)).isZero();
    }

    @Test
    @DisplayName("RISK-001: 검토자는 위험도 높은 순으로 Risk Library 를 조회하고 Finding·Review 로 역추적한다")
    void listReturnsPatternsOrderedBySeverity() throws Exception {
        mockMvc.perform(asReviewer(get("/api/risk-patterns")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].riskPatternId").value(highPatternId))
                .andExpect(jsonPath("$.items[0].findingId").value(highFindingId))
                .andExpect(jsonPath("$.items[0].reviewId").value(reviewId))
                .andExpect(jsonPath("$.items[0].name").value("원금보장 오해"))
                .andExpect(jsonPath("$.items[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[1].severity").value("MEDIUM"))
                .andExpect(jsonPath("$.items[2].severity").value("LOW"));
    }

    @Test
    @DisplayName("RISK-001: severity 필터는 해당 위험도만 남긴다")
    void listFiltersBySeverity() throws Exception {
        mockMvc.perform(asReviewer(get("/api/risk-patterns").param("severity", "HIGH")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].findingId").value(highFindingId));
    }

    @Test
    @DisplayName("RISK-001: personaCode 필터는 그 Persona 가 영향받은 Finding 의 패턴만 남긴다")
    void listFiltersByPersonaCode() throws Exception {
        mockMvc.perform(asReviewer(get("/api/risk-patterns").param("personaCode", "SENIOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].findingId").value(highFindingId));

        mockMvc.perform(asReviewer(get("/api/risk-patterns").param("personaCode", "FINANCIAL_BEGINNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("RISK-001: ruleCode 필터는 그 규칙을 포함한 Pack 으로 분석된 패턴만 남긴다")
    void listFiltersByRuleCode() throws Exception {
        mockMvc.perform(asReviewer(get("/api/risk-patterns").param("ruleCode", "STABILITY_KEYWORD")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].findingId").value(highFindingId))
                .andExpect(jsonPath("$.items[1].findingId").value(lowFindingId));
    }

    @Test
    @DisplayName("RISK-001: 필터를 조합하면 교집합만 남는다")
    void listCombinesFilters() throws Exception {
        mockMvc.perform(asReviewer(get("/api/risk-patterns")
                        .param("severity", "MEDIUM")
                        .param("personaCode", "FINANCIAL_BEGINNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].findingId").value(mediumFindingId));
    }

    @Test
    @DisplayName("RISK-001: 페이징은 필터 조건 전체를 기준으로 집계한다")
    void listPaginates() throws Exception {
        mockMvc.perform(asReviewer(get("/api/risk-patterns").param("page", "1").param("size", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].severity").value("MEDIUM"));
    }

    @Test
    @DisplayName("RISK-001: 상품 담당자가 Risk Library 를 조회하면 403")
    void productManagerCannotReadLibrary() throws Exception {
        mockMvc.perform(asPm(get("/api/risk-patterns")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.FORBIDDEN.name()));
    }

    @Test
    @DisplayName("RISK-001: 인증 헤더가 없으면 401")
    void anonymousCannotReadLibrary() throws Exception {
        mockMvc.perform(get("/api/risk-patterns"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("RISK-001: 정의되지 않은 필터 값은 400")
    void unknownFilterValueIsBadRequest() throws Exception {
        mockMvc.perform(asReviewer(get("/api/risk-patterns").param("severity", "CRITICAL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.VALIDATION_ERROR.name()));
    }

    @Test
    @DisplayName("승격: 검토 승인으로 만들어진 패턴은 항상 DRAFT 다")
    void promoteAlwaysCreatesDraft() {
        Long newFindingId = insertFinding(
                jdbc.queryForObject("SELECT analysis_id FROM findings WHERE id = ?", Long.class, highFindingId),
                "수수료 안내가 본문과 떨어져 있습니다.", "MEDIUM");
        jdbc.update("""
                INSERT INTO review_selected_findings (review_id, finding_id)
                VALUES (?, ?)""", reviewId, newFindingId);
        List<Finding> findings = findingRepository.findAllById(List.of(newFindingId));

        List<Long> promoted = riskPatternService.promote(reviewId, findings);

        assertThat(promoted).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT status FROM risk_patterns WHERE id = ?", String.class, promoted.get(0)))
                .isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("승격: 대상 Review와 다른 Analysis의 Finding이 섞이면 패턴을 하나도 저장하지 않는다")
    void promoteRejectsCrossAnalysisFindingsWithoutPersistingPatterns() {
        Long targetAnalysisId = insertCompletedAnalysis("교차 분석 검증 상품", CORE_PACK_ID, "hash-target");
        Long targetFindingId = insertFinding(targetAnalysisId, "대상 분석의 지적입니다.", "LOW");
        Long targetReviewId = insertApprovedReview(targetAnalysisId, targetFindingId);
        Long foreignAnalysisId = insertCompletedAnalysis("다른 분석 상품", CORE_PACK_ID, "hash-foreign");
        Long foreignFindingId = insertFinding(foreignAnalysisId, "다른 분석의 지적입니다.", "HIGH");
        List<Finding> findings = findingRepository.findAllById(List.of(targetFindingId, foreignFindingId));

        assertThatThrownBy(() -> riskPatternService.promote(targetReviewId, findings))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_FINDING_SELECTION);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM risk_patterns WHERE review_id = ?", Integer.class, targetReviewId)).isZero();
    }

    @Test
    @DisplayName("승격: PENDING Review의 Finding은 패턴을 하나도 저장하지 않는다")
    void promoteRejectsPendingReviewWithoutPersistingPatterns() {
        Long analysisId = insertCompletedAnalysis("승인 전 검토 상품", CORE_PACK_ID, "hash-pending-review");
        Long findingId = insertFinding(analysisId, "승인 전 지적입니다.", "MEDIUM");
        Long pendingReviewId = insertReview(analysisId, "PENDING");

        assertThatThrownBy(() -> riskPatternService.promote(
                pendingReviewId, findingRepository.findAllById(List.of(findingId))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_FINDING_SELECTION);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM risk_patterns WHERE review_id = ?", Integer.class, pendingReviewId)).isZero();
    }

    @Test
    @DisplayName("승격: REJECTED Review의 Finding은 패턴을 하나도 저장하지 않는다")
    void promoteRejectsRejectedReviewWithoutPersistingPatterns() {
        Long analysisId = insertCompletedAnalysis("반려된 검토 상품", CORE_PACK_ID, "hash-rejected-review");
        Long findingId = insertFinding(analysisId, "반려된 지적입니다.", "HIGH");
        Long rejectedReviewId = insertReview(analysisId, "REJECTED");

        assertThatThrownBy(() -> riskPatternService.promote(
                rejectedReviewId, findingRepository.findAllById(List.of(findingId))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_FINDING_SELECTION);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM risk_patterns WHERE review_id = ?", Integer.class, rejectedReviewId)).isZero();
    }

    @Test
    @DisplayName("승격: 같은 Analysis라도 Review에서 선택하지 않은 Finding은 패턴을 하나도 저장하지 않는다")
    void promoteRejectsUnselectedSameAnalysisFindingWithoutPersistingPatterns() {
        Long analysisId = insertCompletedAnalysis("미선택 지적 검증 상품", CORE_PACK_ID, "hash-unselected-finding");
        Long selectedFindingId = insertFinding(analysisId, "선택한 지적입니다.", "LOW");
        Long unselectedFindingId = insertFinding(analysisId, "선택하지 않은 지적입니다.", "MEDIUM");
        Long approvedReviewId = insertApprovedReview(analysisId, selectedFindingId);

        assertThatThrownBy(() -> riskPatternService.promote(
                approvedReviewId, findingRepository.findAllById(List.of(unselectedFindingId))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_FINDING_SELECTION);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM risk_patterns WHERE review_id = ?", Integer.class, approvedReviewId)).isZero();
    }

    @Test
    @DisplayName("Review provenance 조회는 DRAFT·ACTIVE 패턴 ID만 해당 review에서 오름차순으로 반환한다")
    void findIdsByReviewIdIncludesDraftBeforeAndAfterActivation() throws Exception {
        Long draftFindingId = insertFinding(
                jdbc.queryForObject("SELECT analysis_id FROM findings WHERE id = ?", Long.class, highFindingId),
                "중도해지 비용이 분산되어 있습니다.", "MEDIUM");
        jdbc.update("""
                INSERT INTO review_selected_findings (review_id, finding_id)
                VALUES (?, ?)""", reviewId, draftFindingId);
        Long draftPatternId = riskPatternService.promote(
                reviewId, findingRepository.findAllById(List.of(draftFindingId))).get(0);

        assertThat(riskPatternService.findIdsByReviewId(reviewId))
                .containsExactly(highPatternId, lowPatternId, draftPatternId)
                .doesNotContain(unrelatedPatternId);

        String traceId = "risk-status-only-activation-success";
        mockMvc.perform(asReviewer(patch("/api/risk-patterns/{id}", draftPatternId)
                        .header(TRACE_ID_HEADER, traceId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "ACTIVE"))))
                .andExpect(status().isOk());

        assertThat(riskPatternService.findIdsByReviewId(reviewId))
                .containsExactly(highPatternId, lowPatternId, draftPatternId)
                .doesNotContain(unrelatedPatternId);
        assertAuditEvent(traceId, "RISK_PATTERN_ACTIVATED", draftPatternId, "중도해지 비용이 분산되어 있습니다.");
    }

    @Test
    @DisplayName("RISK-002: 검토자가 이름을 다듬어 ACTIVE 로 올린다")
    void reviewerRenamesAndActivates() throws Exception {
        Long draftPatternId = insertPattern(
                insertFinding(jdbc.queryForObject(
                        "SELECT analysis_id FROM findings WHERE id = ?", Long.class, highFindingId),
                        "안정성 표현이 원금보장으로 오인될 가능성이 있습니다.", "HIGH"),
                reviewId, "안정성 표현이 원금보장으로 오인될 가능성이 있습니다.", "HIGH", "DRAFT");

        mockMvc.perform(asReviewer(patch("/api/risk-patterns/{id}", draftPatternId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "원금보장 오해", "status", "ACTIVE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskPatternId").value(draftPatternId))
                .andExpect(jsonPath("$.name").value("원금보장 오해"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 활성화된 뒤에는 GuardFit 보호조치를 붙일 수 있다.
        assertThat(riskPatternService.getActive(draftPatternId).getId()).isEqualTo(draftPatternId);
    }

    @Test
    @DisplayName("RISK-002: status 없이 이름만 고치면 DRAFT 로 남는다")
    void renameKeepsDraft() throws Exception {
        Long draftPatternId = insertPattern(
                insertFinding(jdbc.queryForObject(
                        "SELECT analysis_id FROM findings WHERE id = ?", Long.class, highFindingId),
                        "중도해지 비용이 분산되어 있습니다.", "MEDIUM"),
                reviewId, "중도해지 비용이 분산되어 있습니다.", "MEDIUM", "DRAFT");

        String traceId = "risk-name-update-success";
        mockMvc.perform(asReviewer(patch("/api/risk-patterns/{id}", draftPatternId)
                        .header(TRACE_ID_HEADER, traceId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "중도해지 비용 오인"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("중도해지 비용 오인"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
        assertAuditEvent(traceId, "RISK_PATTERN_UPDATED", draftPatternId, "중도해지 비용 오인");
    }

    @Test
    @DisplayName("RISK-002: ACTIVE 를 DRAFT 로 되돌리면 409")
    void revertingToDraftIsConflict() throws Exception {
        String traceId = "risk-rejected-transition-failure";
        mockMvc.perform(asReviewer(patch("/api/risk-patterns/{id}", highPatternId)
                        .header(TRACE_ID_HEADER, traceId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "DRAFT"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.RISK_PATTERN_ALREADY_ACTIVE.name()));
        assertNoAuditEvent(traceId, highPatternId);
    }

    @Test
    @DisplayName("RISK-002: 상품 담당자의 수정 호출은 403")
    void updateByProductManagerIsForbidden() throws Exception {
        String traceId = "risk-update-authorization-failure";
        mockMvc.perform(asPm(patch("/api/risk-patterns/{id}", highPatternId)
                        .header(TRACE_ID_HEADER, traceId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "임의 변경"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.FORBIDDEN.name()));
        assertNoAuditEvent(traceId, highPatternId);
    }

    @Test
    @DisplayName("RISK-002: 공백 이름 검증 실패는 감사 로그를 남기지 않는다")
    void blankNameWritesNoAuditEvent() throws Exception {
        Long draftPatternId = insertPattern(
                insertFinding(jdbc.queryForObject(
                        "SELECT analysis_id FROM findings WHERE id = ?", Long.class, highFindingId),
                        "공백 이름 검증 대상입니다.", "MEDIUM"),
                reviewId, "저장된 패턴 이름", "MEDIUM", "DRAFT");
        String traceId = "risk-update-validation-failure";

        mockMvc.perform(asReviewer(patch("/api/risk-patterns/{id}", draftPatternId)
                        .header(TRACE_ID_HEADER, traceId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", " "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.RISK_PATTERN_NAME_REQUIRED.name()));

        assertNoAuditEvent(traceId, draftPatternId);
    }

    @Test
    @DisplayName("RISK-001: status 필터로 다듬지 않은 DRAFT 초안만 볼 수 있다")
    void filtersByStatus() throws Exception {
        Long draftPatternId = insertPattern(
                insertFinding(jdbc.queryForObject(
                        "SELECT analysis_id FROM findings WHERE id = ?", Long.class, highFindingId),
                        "형식적 확인 절차만 있습니다.", "LOW"),
                reviewId, "형식적 확인 절차", "LOW", "DRAFT");

        mockMvc.perform(asReviewer(get("/api/risk-patterns").param("status", "DRAFT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].riskPatternId").value(draftPatternId))
                .andExpect(jsonPath("$.items[0].status").value("DRAFT"));
    }

    @Test
    @DisplayName("GuardFit 전제: ACTIVE 가 아닌 패턴은 409 RISK_PATTERN_NOT_ACTIVE")
    void getActiveRejectsDraftPattern() {
        Long draftFindingId = insertFinding(
                jdbc.queryForObject("SELECT analysis_id FROM findings WHERE id = ?", Long.class, highFindingId),
                "표기 위치가 규정과 다릅니다.", "MEDIUM");
        Long draftPatternId = insertPattern(draftFindingId, reviewId, "표기 위치 오류", "MEDIUM", "DRAFT");

        assertThat(riskPatternService.getActive(highPatternId).getId()).isEqualTo(highPatternId);

        assertThatThrownBy(() -> riskPatternService.getActive(draftPatternId))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RISK_PATTERN_NOT_ACTIVE);
    }
}
