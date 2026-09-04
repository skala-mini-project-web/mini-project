package com.crosschecklab.domain.analysis;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosschecklab.analysis.provider.RiskAnalysisProvider;
import com.crosschecklab.analysis.provider.dto.AnalysisRequest;
import com.crosschecklab.analysis.provider.dto.AnalysisResult;
import com.crosschecklab.global.config.AsyncConfig;
import com.crosschecklab.global.error.ErrorCode;
import com.crosschecklab.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// ANA-001~004 통합 검증. 외부 ai-service 없이 테스트 대역으로 전체 흐름을 돌린다.
@Import(AnalysisApiTest.TestBeans.class)
class AnalysisApiTest extends IntegrationTestSupport {

    @TestConfiguration
    static class TestBeans {

        @Bean
        @Primary
        FakeRiskAnalysisProvider fakeRiskAnalysisProvider() {
            return new FakeRiskAnalysisProvider();
        }

        // @Async 를 동기 실행으로 바꿔 202 반환 시점에 백그라운드 작업까지 끝나게 한다.
        @Bean(name = AsyncConfig.ANALYSIS_EXECUTOR)
        Executor analysisTaskExecutor() {
            return new SyncTaskExecutor();
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RiskAnalysisProvider provider;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // V2 시드: 1 = pm_park(PRODUCT_MANAGER, 아래 상품의 소유자), 2 = reviewer_kim(COMPLIANCE_REVIEWER)
    private static final String USER_ID_HEADER = "X-Demo-User-Id";
    private static final String ROLE_HEADER = "X-Demo-Role";

    private Long productId;
    private Long confirmedDocumentId;

    private MockHttpServletRequestBuilder asPm(MockHttpServletRequestBuilder builder) {
        return builder.header(USER_ID_HEADER, "1").header(ROLE_HEADER, "PRODUCT_MANAGER");
    }

    private MockHttpServletRequestBuilder asReviewer(MockHttpServletRequestBuilder builder) {
        return builder.header(USER_ID_HEADER, "2").header(ROLE_HEADER, "COMPLIANCE_REVIEWER");
    }

    private MockHttpServletRequestBuilder traced(MockHttpServletRequestBuilder builder, String traceId) {
        return builder.header("X-Trace-Id", traceId);
    }

    // 컨테이너는 JVM 당 하나라 여기서 만든 변경 가능한 행이 다른 테스트로 새어 나간다.
    // analyses 가 남으면 다른 테스트의 products 삭제가 FK 에 걸리므로 앞뒤로 비운다.
    // append-only audit_events 는 비우지 않고 trace/resource 조건으로 검증 범위를 격리한다.
    @BeforeEach
    void setUp() {
        ((FakeRiskAnalysisProvider) provider).reset();
        clearFixtures();
        productId = jdbc.queryForObject("""
                INSERT INTO products (owner_id, name, product_type, created_at, updated_at)
                VALUES (1, '스마트 인컴 투자상품', 'INVESTMENT', NOW(), NOW())
                RETURNING id""", Long.class);
        confirmedDocumentId = insertDocument(true);
    }

    @AfterEach
    void tearDown() {
        clearFixtures();
    }

    // 참조 순서대로 지운다 (analyses → product_documents → products).
    private void clearFixtures() {
        jdbc.update("DELETE FROM analyses");
        jdbc.update("DELETE FROM product_documents");
        jdbc.update("DELETE FROM products");
    }

    private Long insertDocumentOwnedBy(Long ownerId) {
        Long otherProductId = jdbc.queryForObject("""
                INSERT INTO products (owner_id, name, product_type, created_at, updated_at)
                VALUES (?, '타인 상품', 'INVESTMENT', NOW(), NOW())
                RETURNING id""", Long.class, ownerId);
        return jdbc.queryForObject("""
                INSERT INTO product_documents
                    (product_id, file_name, media_type, storage_key, extract_status, extracted_text,
                     confirmed, created_at, updated_at)
                VALUES (?, '타인_설명서.pdf', 'application/pdf', 'mock://documents/other',
                        'READY', '타인 상품 설명 텍스트입니다.', TRUE, NOW(), NOW())
                RETURNING id""", Long.class, otherProductId);
    }

    private Long insertDocument(boolean confirmed) {
        return jdbc.queryForObject("""
                INSERT INTO product_documents
                    (product_id, file_name, media_type, storage_key, extract_status, extracted_text,
                     confirmed, created_at, updated_at)
                VALUES (?, '스마트인컴_상품설명서.pdf', 'application/pdf', 'mock://documents/guarantee',
                        'READY', '최근 안정적인 수익률을 기록한 투자상품입니다.', ?, NOW(), NOW())
                RETURNING id""", Long.class, productId, confirmed);
    }

    private Long createAnalysis() throws Exception {
        return createAnalysis(null);
    }

    private Long createAnalysis(String traceId) throws Exception {
        MockHttpServletRequestBuilder builder = asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(confirmedDocumentId, List.of(1, 2), List.of(1, 2))));
        if (traceId != null) {
            builder = traced(builder, traceId);
        }
        String body = mockMvc.perform(builder)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("analysisId").asLong();
    }

    private void assertAudit(String traceId, String action, Long resourceId, Long actorId, Long analysisId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT trace_id, actor_id, action, resource_type, resource_id, resource_label, analysis_id
                FROM audit_events
                WHERE trace_id = ?
                  AND action = ?
                  AND resource_type = 'ANALYSIS'
                  AND resource_id = ?
                """, traceId, action, resourceId);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("trace_id")).isEqualTo(traceId);
            assertThat(row.get("actor_id")).isEqualTo(actorId);
            assertThat(row.get("action")).isEqualTo(action);
            assertThat(row.get("resource_type")).isEqualTo("ANALYSIS");
            assertThat(row.get("resource_id")).isEqualTo(resourceId);
            assertThat(row.get("resource_label")).isNull();
            assertThat(row.get("analysis_id")).isEqualTo(analysisId);
        });
    }

    private void assertNoTerminalAudit(String traceId) {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events
                WHERE trace_id = ?
                  AND action IN ('ANALYSIS_COMPLETED', 'ANALYSIS_FAILED')
                """, Long.class, traceId)).isZero();
    }

    private void assertNoAudit(String traceId) {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE trace_id = ?", Long.class, traceId)).isZero();
    }

    private String request(Long documentId, List<Integer> evidenceIds, List<Integer> personaIds) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "productDocumentId", documentId,
                "evidenceDocumentIds", evidenceIds,
                "personaIds", personaIds,
                "redTeamPackId", 1));
    }

    @Test
    @DisplayName("ANA-001·004: 분석을 생성하면 202로 수락되고 riskScore 82 시나리오가 COMPLETED로 저장된다")
    void createAndComplete() throws Exception {
        String traceId = "analysis-create-success";
        mockMvc.perform(traced(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(confirmedDocumentId, List.of(1, 2), List.of(1, 2)))), traceId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.statusUrl").exists())
                .andExpect(jsonPath("$.resultUrl").exists());

        Long analysisId = jdbc.queryForObject("SELECT MAX(id) FROM analyses", Long.class);

        mockMvc.perform(asPm(get("/api/analyses/{id}", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.progress").value(100))
                .andExpect(jsonPath("$.riskScore").value(82))
                .andExpect(jsonPath("$.requiresHumanApproval").value(true))
                .andExpect(jsonPath("$.errorCode").isEmpty());

        mockMvc.perform(asPm(get("/api/analyses/{id}/result", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskScore").value(82))
                .andExpect(jsonPath("$.sourceDocument.fileName").value("스마트인컴_상품설명서.pdf"))
                .andExpect(jsonPath("$.groundingDocuments.length()").value(2))
                .andExpect(jsonPath("$.findings.length()").value(1))
                .andExpect(jsonPath("$.findings[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.findings[0].affectedPersonaCodes[0]").value("FINANCIAL_BEGINNER"))
                .andExpect(jsonPath("$.findings[0].evidenceReferences[0].sourceType").value("INTERNAL_POLICY"));

        assertAudit(traceId, "ANALYSIS_CREATED", analysisId, 1L, analysisId);
        assertAudit(traceId, "ANALYSIS_COMPLETED", analysisId, null, analysisId);
    }

    @Test
    @DisplayName("비동기 스레드의 MDC가 달라도 완료 감사에는 요청 이벤트의 원래 trace가 보존된다")
    void completionPreservesRequestTraceFromEvent() throws Exception {
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        Function<AnalysisRequest, AnalysisResult> behavior = request -> {
            MDC.put("traceId", "async-thread-trace");
            return new AnalysisResult(0, "trace-test", "trace-test", List.of());
        };
        ReflectionTestUtils.setField(fake, "behavior", behavior);
        String traceId = "analysis-original-request-trace";

        Long analysisId = createAnalysis(traceId);

        assertAudit(traceId, "ANALYSIS_CREATED", analysisId, 1L, analysisId);
        assertAudit(traceId, "ANALYSIS_COMPLETED", analysisId, null, analysisId);
        assertNoAudit("async-thread-trace");
    }

    @Test
    @DisplayName("Provider 에는 확정 텍스트와 선택한 코드가 전달된다")
    void providerRequestIsResolved() throws Exception {
        createAnalysis();

        var request = ((FakeRiskAnalysisProvider) provider).lastRequest();
        assertThat(request.confirmedText()).isEqualTo("최근 안정적인 수익률을 기록한 투자상품입니다.");
        assertThat(request.scenarioCode()).isEqualTo("GUARANTEE_MISUNDERSTANDING_HIGH");
        assertThat(request.redTeamPackCode()).isEqualTo("CORE_FINANCIAL_RISK_V1");
        assertThat(request.ruleCodes()).hasSize(6);
        assertThat(request.personaCodes()).hasSize(2);
        assertThat(request.evidenceDocuments()).hasSize(2)
                .allSatisfy(evidence -> assertThat(evidence.content()).isNotBlank());
    }

    @Test
    @DisplayName("ANA-002: GET Polling 은 상태를 바꾸지 않는다")
    void pollingDoesNotChangeState() throws Exception {
        Long analysisId = createAnalysis();
        String before = jdbc.queryForObject("SELECT updated_at::text FROM analyses WHERE id = ?", String.class, analysisId);

        mockMvc.perform(asPm(get("/api/analyses/{id}", analysisId))).andExpect(status().isOk());
        mockMvc.perform(asPm(get("/api/analyses/{id}", analysisId))).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT updated_at::text FROM analyses WHERE id = ?", String.class, analysisId))
                .isEqualTo(before);
    }

    @Test
    @DisplayName("확정되지 않은 문서로 분석을 요청하면 409 DOCUMENT_NOT_CONFIRMED")
    void documentNotConfirmed() throws Exception {
        Long documentId = insertDocument(false);
        String traceId = "analysis-document-not-confirmed";

        mockMvc.perform(traced(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(documentId, List.of(1), List.of(1)))), traceId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DOCUMENT_NOT_CONFIRMED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("Persona 를 4개 선택하면 400 INVALID_SELECTION_COUNT")
    void invalidSelectionCount() throws Exception {
        String traceId = "analysis-invalid-selection";
        mockMvc.perform(traced(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(confirmedDocumentId, List.of(1), List.of(1, 2, 3, 4)))), traceId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SELECTION_COUNT"));
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("존재하지 않는 근거 문서를 선택하면 400 INVALID_EVIDENCE_DOCUMENT")
    void invalidEvidenceDocument() throws Exception {
        String traceId = "analysis-invalid-evidence";
        mockMvc.perform(traced(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(confirmedDocumentId, List.of(999), List.of(1)))), traceId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_EVIDENCE_DOCUMENT"));
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("동일 입력으로 다시 요청하면 409 DUPLICATE_ANALYSIS_REQUEST")
    void duplicateRequest() throws Exception {
        createAnalysis();
        String traceId = "analysis-duplicate";

        mockMvc.perform(traced(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(confirmedDocumentId, List.of(1, 2), List.of(1, 2)))), traceId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_ANALYSIS_REQUEST"));
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("ANA-003: 일시 장애로 실패하면 200 FAILED·retryable=true 로 보이고 재시도하면 완료된다")
    void retryAfterTemporaryFailure() throws Exception {
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        fake.failWith(ErrorCode.AI_SERVICE_TEMPORARY_FAILURE, true);
        String initialTraceId = "analysis-temporary-failure";
        Long analysisId = createAnalysis(initialTraceId);
        String traceId = "analysis-retry-success";

        mockMvc.perform(asPm(get("/api/analyses/{id}", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.errorCode").value("AI_SERVICE_TEMPORARY_FAILURE"))
                .andExpect(jsonPath("$.message").isNotEmpty());

        fake.reset();
        mockMvc.perform(traced(asPm(post("/api/analyses/{id}/retry", analysisId)), traceId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.analysisId").value(analysisId));

        mockMvc.perform(asPm(get("/api/analyses/{id}", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.riskScore").value(82))
                .andExpect(jsonPath("$.retryable").value(false));
        assertAudit(initialTraceId, "ANALYSIS_CREATED", analysisId, 1L, analysisId);
        assertAudit(initialTraceId, "ANALYSIS_FAILED", analysisId, null, analysisId);
        assertAudit(traceId, "ANALYSIS_RETRIED", analysisId, 1L, analysisId);
        assertAudit(traceId, "ANALYSIS_COMPLETED", analysisId, null, analysisId);
    }

    @Test
    @DisplayName("계약 위반 실패는 retryable=false 이고 재시도 시 409 ANALYSIS_NOT_RETRYABLE")
    void notRetryable() throws Exception {
        ((FakeRiskAnalysisProvider) provider).failWith(ErrorCode.PROVIDER_RESPONSE_INVALID, false);
        String failureTraceId = "analysis-terminal-failure";
        Long analysisId = createAnalysis(failureTraceId);
        String traceId = "analysis-retry-not-retryable";

        mockMvc.perform(asPm(get("/api/analyses/{id}", analysisId)))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.retryable").value(false));

        mockMvc.perform(traced(asPm(post("/api/analyses/{id}/retry", analysisId)), traceId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ANALYSIS_NOT_RETRYABLE"));
        assertAudit(failureTraceId, "ANALYSIS_CREATED", analysisId, 1L, analysisId);
        assertAudit(failureTraceId, "ANALYSIS_FAILED", analysisId, null, analysisId);
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("Provider 호출 뒤 재시도가 현재 회차가 되면 이전 회차는 새 결과와 감사를 덮어쓰지 않는다")
    void supersededJobCannotFinalizeNewerRetry() throws Exception {
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        String oldTraceId = "analysis-stale-execution";
        String retryTraceId = "analysis-newer-retry";
        AtomicReference<Long> currentAnalysisId = new AtomicReference<>();
        CountDownLatch oldProviderEntered = new CountDownLatch(1);
        CountDownLatch releaseOldProvider = new CountDownLatch(1);
        Function<AnalysisRequest, AnalysisResult> staleResult = request -> {
            currentAnalysisId.set(request.analysisId());
            oldProviderEntered.countDown();
            try {
                if (!releaseOldProvider.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("이전 Provider 결과 해제 대기 시간이 초과되었습니다.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("이전 Provider 결과 대기가 중단되었습니다.", e);
            }
            return new AnalysisResult(7, "stale-model", "stale-prompt", List.of());
        };
        ReflectionTestUtils.setField(fake, "behavior", staleResult);

        CompletableFuture<Long> originalCreate = CompletableFuture.supplyAsync(() -> {
            try {
                return createAnalysis(oldTraceId);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).orTimeout(10, TimeUnit.SECONDS);

        assertThat(oldProviderEntered.await(5, TimeUnit.SECONDS)).isTrue();
        Long analysisId = currentAnalysisId.get();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Analysis analysis = analysisRepository.findWithLockById(analysisId).orElseThrow();
                analysis.fail(ErrorCode.AI_SERVICE_TEMPORARY_FAILURE, true);
                analysisRepository.flush();
            });
            mockMvc.perform(asPm(get("/api/analyses/{id}", analysisId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FAILED"))
                    .andExpect(jsonPath("$.retryable").value(true))
                    .andExpect(jsonPath("$.errorCode").value("AI_SERVICE_TEMPORARY_FAILURE"));

            fake.reset();
            mockMvc.perform(traced(asPm(post("/api/analyses/{id}/retry", analysisId)), retryTraceId))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.analysisId").value(analysisId));

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("""
                            SELECT COUNT(*) FROM audit_events
                            WHERE trace_id = ?
                              AND action = 'ANALYSIS_COMPLETED'
                              AND resource_type = 'ANALYSIS'
                              AND resource_id = ?
                            """, Long.class, retryTraceId, analysisId)).isEqualTo(1L));
        } finally {
            releaseOldProvider.countDown();
        }
        assertThat(originalCreate.join()).isEqualTo(analysisId);

        Map<String, Object> analysis = jdbc.queryForMap("""
                SELECT status, risk_score, model_version, prompt_version
                FROM analyses
                WHERE id = ?
                """, analysisId);
        assertThat(analysis)
                .containsEntry("status", "COMPLETED")
                .containsEntry("risk_score", 82)
                .containsEntry("model_version", "mock-risk-v1")
                .containsEntry("prompt_version", "mock-prompt-v1");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM findings WHERE analysis_id = ?", Long.class, analysisId)).isEqualTo(1L);
        assertAudit(oldTraceId, "ANALYSIS_CREATED", analysisId, 1L, analysisId);
        assertNoTerminalAudit(oldTraceId);
        assertAudit(retryTraceId, "ANALYSIS_RETRIED", analysisId, 1L, analysisId);
        assertAudit(retryTraceId, "ANALYSIS_COMPLETED", analysisId, null, analysisId);
    }

    @Test
    @DisplayName("완료되지 않은 분석의 결과를 조회하면 409 ANALYSIS_NOT_COMPLETED")
    void resultBeforeCompleted() throws Exception {
        ((FakeRiskAnalysisProvider) provider).failWith(ErrorCode.PROVIDER_RESPONSE_INVALID, false);
        Long analysisId = createAnalysis();

        mockMvc.perform(asPm(get("/api/analyses/{id}/result", analysisId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ANALYSIS_NOT_COMPLETED"));
    }

    @Test
    @DisplayName("인증 헤더가 없으면 401")
    void unauthenticated() throws Exception {
        mockMvc.perform(get("/api/analyses/{id}", 1L))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("다른 사용자의 문서로는 분석을 생성할 수 없다 (403)")
    void cannotCreateOnOthersDocument() throws Exception {
        Long othersDocumentId = insertDocumentOwnedBy(2L);
        String traceId = "analysis-create-others";

        mockMvc.perform(traced(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(othersDocumentId, List.of(1), List.of(1)))), traceId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_OWNERSHIP"));
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("검토자는 분석을 생성할 수 없다 (403)")
    void reviewerCannotCreate() throws Exception {
        String traceId = "analysis-create-wrong-role";
        mockMvc.perform(traced(asReviewer(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(confirmedDocumentId, List.of(1), List.of(1)))), traceId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("검토자는 담당이 아닌 분석도 조회할 수 있다")
    void reviewerCanRead() throws Exception {
        Long analysisId = createAnalysis();

        mockMvc.perform(asReviewer(get("/api/analyses/{id}", analysisId)))
                .andExpect(status().isOk());
        mockMvc.perform(asReviewer(get("/api/analyses/{id}/result", analysisId)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("진행 중인 분석은 재시도할 수 없다 (409)")
    void cannotRetryWhileRunning() throws Exception {
        Long analysisId = createAnalysis();
        jdbc.update("UPDATE analyses SET status = 'RUNNING', updated_at = NOW() WHERE id = ?", analysisId);
        String traceId = "analysis-retry-running";

        mockMvc.perform(traced(asPm(post("/api/analyses/{id}/retry", analysisId)), traceId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ANALYSIS_ALREADY_RUNNING"));
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("멈춘 지 오래된 RUNNING 분석은 재시도로 되살릴 수 있다")
    void canRetryStaleRunning() throws Exception {
        Long analysisId = createAnalysis();
        jdbc.update("UPDATE analyses SET status = 'RUNNING', updated_at = NOW() - INTERVAL '10 minutes'"
                + " WHERE id = ?", analysisId);

        mockMvc.perform(asPm(post("/api/analyses/{id}/retry", analysisId)))
                .andExpect(status().isAccepted());

        mockMvc.perform(asPm(get("/api/analyses/{id}", analysisId)))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("소유자가 아니면 재시도할 수 없다 (403)")
    void cannotRetryOthersAnalysis() throws Exception {
        ((FakeRiskAnalysisProvider) provider).failWith(ErrorCode.AI_SERVICE_TEMPORARY_FAILURE, true);
        Long analysisId = createAnalysis();
        String traceId = "analysis-retry-others";

        mockMvc.perform(traced(asReviewer(post("/api/analyses/{id}/retry", analysisId)), traceId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_OWNERSHIP"));
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("없는 분석을 조회하면 404")
    void notFound() throws Exception {
        mockMvc.perform(asPm(get("/api/analyses/{id}", 999999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }
}
