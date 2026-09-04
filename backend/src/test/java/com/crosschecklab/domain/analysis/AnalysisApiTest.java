package com.crosschecklab.domain.analysis;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosschecklab.analysis.provider.RiskAnalysisProvider;
import com.crosschecklab.analysis.provider.dto.AnalysisRequest;
import com.crosschecklab.analysis.provider.dto.AnalysisResult;
import com.crosschecklab.analysis.provider.dto.FindingPayload;
import com.crosschecklab.analysis.rag.EvidenceChunkIndexer;
import com.crosschecklab.analysis.rag.PgVectorEvidenceRetriever;
import com.crosschecklab.analysis.rag.RagRetrievedChunk;
import com.crosschecklab.domain.audit.AuditAction;
import com.crosschecklab.domain.audit.AuditEvent;
import com.crosschecklab.domain.audit.AuditEventRepository;
import com.crosschecklab.global.common.enums.Severity;
import com.crosschecklab.global.config.AsyncConfig;
import com.crosschecklab.global.error.ErrorCode;
import com.crosschecklab.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// ANA-001~004 통합 검증. 외부 ai-service 없이 테스트 대역으로 전체 흐름을 돌린다.
@Import(AnalysisApiTest.TestBeans.class)
class AnalysisApiTest extends IntegrationTestSupport {

    private static final String TEST_CHUNKING_VERSION = "analysis-api-test-v1";
    private static final String TEST_EMBEDDING_MODEL = "analysis-api-test-embedding";
    private static final Map<Long, String> TEST_RETRIEVED_CONTEXTS = Map.of(
            1L, "“안정”, “보장”, “확정”과 같은 표현이 있으면 원금손실 가능성과 변동 수익 정정문을 "
                    + "같은 페이지, 같은 화면, 같은 음성 구간에 표시한다.",
            2L, "안정, 보장 또는 확정을 연상시키는 표현을 사용한 경우 동일한 전달 단위에서 "
                    + "그 표현의 한계와 반대되는 손실 가능성을 명확히 정정한다.");

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

        @Bean
        @Primary
        EvidenceChunkIndexer testEvidenceChunkIndexer(JdbcTemplate jdbcTemplate) {
            return new TestEvidenceChunkIndexer(jdbcTemplate);
        }

        @Bean
        @Primary
        PgVectorEvidenceRetriever testEvidenceRetriever(JdbcTemplate jdbcTemplate) {
            return new TestEvidenceRetriever(jdbcTemplate);
        }
    }

    static class TestEvidenceChunkIndexer extends EvidenceChunkIndexer {

        private final JdbcTemplate jdbcTemplate;

        TestEvidenceChunkIndexer(JdbcTemplate jdbcTemplate) {
            super(jdbcTemplate, null);
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public IndexingResult indexSelected(Collection<Long> selectedEvidenceDocumentIds) {
            List<Long> selectedIds = normalizedSelectedIds(selectedEvidenceDocumentIds);
            for (Long evidenceDocumentId : selectedIds) {
                String chunkText = testContext(evidenceDocumentId);
                jdbcTemplate.update("""
                                INSERT INTO evidence_document_chunks (
                                    evidence_document_id, source_hash, chunk_ordinal, chunking_version,
                                    chunk_hash, chunk_text, embedding_model, embedding
                                )
                                SELECT id, ?, 0, ?, ?, ?, ?,
                                       (ARRAY[1.0::real] || array_fill(0.0::real, ARRAY[1023]))::vector
                                FROM evidence_documents
                                WHERE id = ? AND active = TRUE
                                ON CONFLICT (
                                    evidence_document_id, source_hash, chunking_version,
                                    embedding_model, chunk_ordinal
                                ) DO UPDATE SET
                                    chunk_hash = EXCLUDED.chunk_hash,
                                    chunk_text = EXCLUDED.chunk_text,
                                    created_at = CURRENT_TIMESTAMP
                                """,
                        testSourceHash(evidenceDocumentId),
                        TEST_CHUNKING_VERSION,
                        testChunkHash(evidenceDocumentId),
                        chunkText,
                        TEST_EMBEDDING_MODEL,
                        evidenceDocumentId);
            }
            return new IndexingResult(selectedIds.size(), 0, selectedIds.size());
        }
    }

    static class TestEvidenceRetriever extends PgVectorEvidenceRetriever {

        private final JdbcTemplate jdbcTemplate;

        TestEvidenceRetriever(JdbcTemplate jdbcTemplate) {
            super(jdbcTemplate, null);
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public List<RagRetrievedChunk> retrieve(
                String query, Collection<Long> selectedEvidenceDocumentIds) {
            List<Long> selectedIds = normalizedSelectedIds(selectedEvidenceDocumentIds);
            List<RagRetrievedChunk> contexts = new java.util.ArrayList<>();
            for (int index = 0; index < selectedIds.size() && index < TOP_K; index++) {
                Long evidenceDocumentId = selectedIds.get(index);
                int rank = index + 1;
                contexts.add(jdbcTemplate.queryForObject("""
                                SELECT id AS chunk_id, evidence_document_id, source_hash, chunk_hash,
                                       chunk_ordinal, chunking_version, embedding_model, chunk_text,
                                       ?::integer AS rank, ?::double precision AS similarity
                                FROM evidence_document_chunks
                                WHERE evidence_document_id = ?
                                  AND source_hash = ?
                                  AND chunking_version = ?
                                  AND embedding_model = ?
                                """,
                        (resultSet, rowNumber) -> new RagRetrievedChunk(
                                resultSet.getLong("chunk_id"),
                                resultSet.getLong("evidence_document_id"),
                                resultSet.getString("source_hash"),
                                resultSet.getString("chunk_hash"),
                                resultSet.getInt("chunk_ordinal"),
                                resultSet.getString("chunking_version"),
                                resultSet.getString("embedding_model"),
                                resultSet.getString("chunk_text"),
                                resultSet.getInt("rank"),
                                resultSet.getDouble("similarity")),
                        rank,
                        1.0d - (index * 0.1d),
                        evidenceDocumentId,
                        testSourceHash(evidenceDocumentId),
                        TEST_CHUNKING_VERSION,
                        TEST_EMBEDDING_MODEL));
            }
            return List.copyOf(contexts);
        }
    }

    private static List<Long> normalizedSelectedIds(Collection<Long> selectedEvidenceDocumentIds) {
        return new LinkedHashSet<>(selectedEvidenceDocumentIds).stream().sorted().toList();
    }

    private static String testContext(Long evidenceDocumentId) {
        String context = TEST_RETRIEVED_CONTEXTS.get(evidenceDocumentId);
        if (context == null) {
            throw new IllegalArgumentException(
                    "분석 API 테스트 검색 문맥이 없는 근거 문서입니다: " + evidenceDocumentId);
        }
        return context;
    }

    private static String testSourceHash(Long evidenceDocumentId) {
        return (evidenceDocumentId == 1L ? "1" : "2").repeat(64);
    }

    private static String testChunkHash(Long evidenceDocumentId) {
        return (evidenceDocumentId == 1L ? "a" : "b").repeat(64);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RiskAnalysisProvider provider;

    @MockitoSpyBean
    private AuditEventRepository auditEventRepository;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // V2 시드: 1 = pm_park(PRODUCT_MANAGER, 아래 상품의 소유자), 2 = reviewer_kim(COMPLIANCE_REVIEWER)
    private static final String USER_ID_HEADER = "X-Demo-User-Id";
    private static final String ROLE_HEADER = "X-Demo-Role";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private Long productId;
    private Long confirmedDocumentId;
    private long idempotencyKeySequence;

    private MockHttpServletRequestBuilder asPm(MockHttpServletRequestBuilder builder) {
        return builder.header(USER_ID_HEADER, "1").header(ROLE_HEADER, "PRODUCT_MANAGER");
    }

    private MockHttpServletRequestBuilder asReviewer(MockHttpServletRequestBuilder builder) {
        return builder.header(USER_ID_HEADER, "2").header(ROLE_HEADER, "COMPLIANCE_REVIEWER");
    }

    private MockHttpServletRequestBuilder withIdempotencyKey(MockHttpServletRequestBuilder builder) {
        return withIdempotencyKey(builder, "analysis-api-test-" + ++idempotencyKeySequence);
    }

    private MockHttpServletRequestBuilder withIdempotencyKey(
            MockHttpServletRequestBuilder builder, String idempotencyKey) {
        return builder.header(IDEMPOTENCY_KEY_HEADER, idempotencyKey);
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
        jdbc.update("DELETE FROM idempotency_claims");
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
        MockHttpServletRequestBuilder builder = withIdempotencyKey(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(confirmedDocumentId, List.of(1, 2), List.of(1, 2)))));
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

    private void assertSingleTerminalAudit(
            String traceId, String action, Long resourceId, Long actorId, Long analysisId) {
        assertAudit(traceId, action, resourceId, actorId, analysisId);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events
                WHERE trace_id = ?
                  AND action IN ('ANALYSIS_COMPLETED', 'ANALYSIS_FAILED')
                  AND resource_type = 'ANALYSIS'
                  AND resource_id = ?
                """, Long.class, traceId, resourceId)).isEqualTo(1L);
    }

    private void failTerminalAuditPersistence(String traceId) {
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditEventRepository)
                .save(argThat((AuditEvent event) ->
                        traceId.equals(event.getTraceId())
                                && (event.getAction() == AuditAction.ANALYSIS_COMPLETED
                                || event.getAction() == AuditAction.ANALYSIS_FAILED)));
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

    private Long confirmFact(String text, String verificationStatus) throws Exception {
        mockMvc.perform(asPm(patch("/api/documents/{documentId}/text", confirmedDocumentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("extractedText", text, "confirmed", true)))))
                .andExpect(status().isOk());

        String response = mockMvc.perform(asPm(get(
                        "/api/product-documents/{documentId}/ground-truth-facts", confirmedDocumentId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode fact = objectMapper.readTree(response).get("items").get(0);
        Long factId = fact.get("factId").asLong();
        if (!"CANDIDATE".equals(verificationStatus)) {
            mockMvc.perform(asPm(put("/api/ground-truth-facts/{factId}/verification", factId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "verificationStatus", verificationStatus,
                                    "value", text)))))
                    .andExpect(status().isOk());
        }
        return factId;
    }

    private AnalysisResult resultCiting(AnalysisRequest request, Long factId) {
        return new AnalysisResult(82, "fact-aware-model", "fact-aware-prompt", List.of(new FindingPayload(
                "검증된 사실을 인용한 분석 결과입니다.",
                Severity.HIGH,
                List.of(),
                List.of(request.retrievedContexts().getFirst().chunkId()),
                List.of(factId),
                "검증된 사실을 기준으로 설명하세요.")));
    }

    private AnalysisResult resultCitingChunks(List<Long> chunkIds) {
        return new AnalysisResult(82, "chunk-aware-model", "chunk-aware-prompt", List.of(new FindingPayload(
                "검색된 근거 청크를 인용한 분석 결과입니다.",
                Severity.HIGH,
                List.of(),
                chunkIds,
                "검색된 근거를 기준으로 설명하세요.")));
    }

    @Test
    @DisplayName("ANA-001·004: 분석을 생성하면 202로 수락되고 riskScore 82 시나리오가 COMPLETED로 저장된다")
    void createAndComplete() throws Exception {
        String traceId = "analysis-create-success";
        mockMvc.perform(traced(withIdempotencyKey(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(confirmedDocumentId, List.of(1, 2), List.of(1, 2))))), traceId))
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
                .andExpect(jsonPath("$.findings[0].evidenceReferences[0].sourceType").value("INTERNAL_POLICY"))
                .andExpect(jsonPath("$.findings[0].evidenceReferences[0].evidenceDocumentId").value(1))
                .andExpect(jsonPath("$.findings[0].evidenceReferences[0].excerpt")
                        .value(TEST_RETRIEVED_CONTEXTS.get(1L)));
        assertThat(jdbc.queryForMap("""
                SELECT er.evidence_document_id, er.excerpt
                FROM evidence_references er
                JOIN findings f ON f.id = er.finding_id
                WHERE f.analysis_id = ?
                """, analysisId))
                .containsEntry("evidence_document_id", 1L)
                .containsEntry("excerpt", TEST_RETRIEVED_CONTEXTS.get(1L));

        assertAudit(traceId, "ANALYSIS_CREATED", analysisId, 1L, analysisId);
        assertSingleTerminalAudit(traceId, "ANALYSIS_COMPLETED", analysisId, null, analysisId);
    }

    @Test
    @DisplayName("#46: 같은 키와 정규화된 같은 요청은 최초 CREATED 응답을 재생하고 부수 효과를 반복하지 않는다")
    void sameIdempotencyKeyReplaysCreatedResponse() throws Exception {
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        AtomicInteger providerCalls = new AtomicInteger();
        ReflectionTestUtils.setField(fake, "behavior",
                (Function<AnalysisRequest, AnalysisResult>) request -> {
                    providerCalls.incrementAndGet();
                    return new AnalysisResult(0, "idempotency-model", "idempotency-prompt", List.of());
                });
        String key = "analysis-replay-key";
        String firstTraceId = "analysis-replay-first";
        String replayTraceId = "analysis-replay-second";

        String firstBody = mockMvc.perform(traced(withIdempotencyKey(asPm(post("/api/analyses")
                                .header("X-Demo-Scenario", "  ORIGINAL_SCENARIO  ")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request(confirmedDocumentId, List.of(2, 1, 2), List.of(2, 1, 2)))),
                        key), firstTraceId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();

        // 재생 경로가 현재 문서 상태를 다시 검증하지 않는지 확인한다.
        jdbc.update("UPDATE product_documents SET confirmed = FALSE WHERE id = ?", confirmedDocumentId);
        String replayBody = mockMvc.perform(traced(withIdempotencyKey(asPm(post("/api/analyses")
                                .header("X-Demo-Scenario", "ORIGINAL_SCENARIO")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request(confirmedDocumentId, List.of(1, 2), List.of(1, 2)))),
                        key), replayTraceId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(replayBody).get("analysisId").asLong())
                .isEqualTo(objectMapper.readTree(firstBody).get("analysisId").asLong());
        assertThat(providerCalls).hasValue(1);
        assertThat(fake.lastRequest().scenarioCode()).isEqualTo("ORIGINAL_SCENARIO");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analyses", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_claims", Long.class)).isEqualTo(1L);
        assertNoAudit(replayTraceId);
    }

    @Test
    @DisplayName("#46: 같은 키를 다른 요청 의미에 재사용하면 409 IDEMPOTENCY_KEY_REUSED")
    void sameIdempotencyKeyWithDifferentFingerprintConflicts() throws Exception {
        String key = "analysis-conflict-key";
        mockMvc.perform(withIdempotencyKey(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(confirmedDocumentId, List.of(1), List.of(1)))), key))
                .andExpect(status().isAccepted());

        String traceId = "analysis-key-reused";
        mockMvc.perform(traced(withIdempotencyKey(asPm(post("/api/analyses")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request(confirmedDocumentId, List.of(2), List.of(1)))),
                        key), traceId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analyses", Long.class)).isEqualTo(1L);
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("#46: 같은 키의 동시 생성 요청은 하나의 분석과 Provider 호출만 만든다")
    void concurrentSameKeyCreatesOnce() throws Exception {
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        AtomicInteger providerCalls = new AtomicInteger();
        ReflectionTestUtils.setField(fake, "behavior",
                (Function<AnalysisRequest, AnalysisResult>) request -> {
                    providerCalls.incrementAndGet();
                    return new AnalysisResult(0, "concurrent-model", "concurrent-prompt", List.of());
                });
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        String key = "analysis-concurrent-key";

        Function<String, JsonNode> create = traceId -> {
            ready.countDown();
            try {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("동시 생성 시작 대기 시간이 초과되었습니다.");
                }
                String body = mockMvc.perform(traced(withIdempotencyKey(asPm(post("/api/analyses")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(request(
                                                confirmedDocumentId, List.of(1, 2), List.of(1, 2)))),
                                key), traceId))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.status").value("CREATED"))
                        .andReturn().getResponse().getContentAsString();
                return objectMapper.readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        CompletableFuture<JsonNode> first = CompletableFuture.supplyAsync(
                () -> create.apply("analysis-concurrent-first"));
        CompletableFuture<JsonNode> second = CompletableFuture.supplyAsync(
                () -> create.apply("analysis-concurrent-second"));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        JsonNode firstResponse = first.orTimeout(10, TimeUnit.SECONDS).join();
        JsonNode secondResponse = second.orTimeout(10, TimeUnit.SECONDS).join();
        assertThat(firstResponse.get("analysisId").asLong())
                .isEqualTo(secondResponse.get("analysisId").asLong());
        assertThat(providerCalls).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analyses", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_claims", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events
                WHERE trace_id IN ('analysis-concurrent-first', 'analysis-concurrent-second')
                  AND action = 'ANALYSIS_CREATED'
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("#46: Idempotency-Key 범위는 요청 actor별로 분리된다")
    void idempotencyKeyIsActorScoped() throws Exception {
        String key = "actor-scoped-key";
        mockMvc.perform(withIdempotencyKey(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(confirmedDocumentId, List.of(1), List.of(1)))), key))
                .andExpect(status().isAccepted());

        mockMvc.perform(withIdempotencyKey(asReviewer(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(confirmedDocumentId, List.of(1), List.of(1)))), key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM idempotency_claims
                WHERE actor_id = 1 AND idempotency_key = ?
                """, Long.class, key)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM idempotency_claims
                WHERE actor_id = 2 AND idempotency_key = ?
                """, Long.class, key)).isZero();
    }

    @Test
    @DisplayName("#46: 생성 요청의 Idempotency-Key는 필수이며 blank 또는 255자를 넘을 수 없다")
    void createRequiresValidIdempotencyKey() throws Exception {
        String body = request(confirmedDocumentId, List.of(1), List.of(1));

        mockMvc.perform(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        mockMvc.perform(withIdempotencyKey(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)), "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        mockMvc.perform(withIdempotencyKey(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)), "k".repeat(256)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_claims", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analyses", Long.class)).isZero();
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
        assertSingleTerminalAudit(traceId, "ANALYSIS_COMPLETED", analysisId, null, analysisId);
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
        assertThat(request.selectedEvidenceDocumentIds()).containsExactly(1L, 2L);
        assertThat(request.retrievedContexts())
                .extracting(
                        AnalysisRequest.RetrievedContextPayload::evidenceDocumentId,
                        AnalysisRequest.RetrievedContextPayload::chunkText,
                        AnalysisRequest.RetrievedContextPayload::rank)
                .containsExactly(
                        tuple(1L, TEST_RETRIEVED_CONTEXTS.get(1L), 1),
                        tuple(2L, TEST_RETRIEVED_CONTEXTS.get(2L), 2));
        assertThat(request.retrievedContexts())
                .allSatisfy(context -> assertThat(context.chunkId()).isNotNull());
        JsonNode providerPayload = objectMapper.valueToTree(request);
        assertThat(providerPayload.has("selectedEvidenceDocumentIds")).isTrue();
        assertThat(providerPayload.has("retrievedContexts")).isTrue();
        assertThat(providerPayload.has("evidenceDocuments")).isFalse();
        assertThat(providerPayload.get("retrievedContexts").get(0).has("chunkText")).isTrue();
        assertThat(providerPayload.get("retrievedContexts").get(0).has("content")).isFalse();
    }

    @Test
    @DisplayName("#43: VERIFIED가 아닌 사실은 분석 snapshot과 Provider 요청에 포함되지 않는다")
    void nonVerifiedFactsAreNotAccepted() throws Exception {
        confirmFact("검증에서 제외할 사실", "REJECTED");

        Long analysisId = createAnalysis();

        assertThat(((FakeRiskAnalysisProvider) provider).lastRequest().knownFacts()).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM analysis_ground_truth_fact_snapshots
                WHERE analysis_id = ?
                """, Long.class, analysisId)).isZero();
        mockMvc.perform(asPm(get("/api/analyses/{id}/result", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groundTruthFacts").isEmpty());
    }

    @Test
    @DisplayName("분석이 생성된 문서는 재확정할 수 없어 수락 시점 사실 snapshot이 바뀌지 않는다")
    void documentWithAnalysisCannotBeReconfirmed() throws Exception {
        String acceptedValue = "분석 수락 시점에 검증된 사실";
        Long factId = confirmFact(acceptedValue, "VERIFIED");
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        fake.failWith(ErrorCode.AI_SERVICE_TEMPORARY_FAILURE, true);
        Long analysisId = createAnalysis();

        assertThat(jdbc.queryForMap("""
                SELECT ground_truth_fact_id, label, value
                FROM analysis_ground_truth_fact_snapshots
                WHERE analysis_id = ?
                """, analysisId))
                .containsEntry("ground_truth_fact_id", factId)
                .containsEntry("label", "확정 문서 텍스트")
                .containsEntry("value", acceptedValue);
        assertThat(fake.lastRequest().knownFacts())
                .singleElement()
                .satisfies(fact -> {
                    assertThat(fact.factId()).isEqualTo(factId);
                    assertThat(fact.text()).isEqualTo(acceptedValue);
                });

        mockMvc.perform(asPm(patch("/api/documents/{documentId}/text", confirmedDocumentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "extractedText", "문서 재확정으로 바뀐 현재 사실",
                                "confirmed", true)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DOCUMENT_ALREADY_ANALYZED"));

        assertThat(jdbc.queryForObject("""
                SELECT value
                FROM analysis_ground_truth_fact_snapshots
                WHERE analysis_id = ?
                """, String.class, analysisId)).isEqualTo(acceptedValue);
    }

    @Test
    @DisplayName("#43: Provider가 수락된 factId를 인용하면 결과에 그 참조가 노출된다")
    void acceptedProviderFactReferencesAreVisibleInResult() throws Exception {
        Long factId = confirmFact("Provider가 인용할 검증된 사실", "VERIFIED");
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        ReflectionTestUtils.setField(fake, "behavior",
                (Function<AnalysisRequest, AnalysisResult>) request -> resultCiting(request, factId));

        Long analysisId = createAnalysis();

        mockMvc.perform(asPm(get("/api/analyses/{id}/result", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groundTruthFacts[0].factId").value(factId));
    }

    @Test
    @DisplayName("#43: Provider가 수락되지 않은 factId를 인용하면 결과를 저장하지 않는다")
    void unacceptedProviderFactReferenceIsRejected() throws Exception {
        Long acceptedFactId = confirmFact("허용된 검증 사실", "VERIFIED");
        Long unknownFactId = acceptedFactId + 999_999L;
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        ReflectionTestUtils.setField(fake, "behavior",
                (Function<AnalysisRequest, AnalysisResult>) request -> resultCiting(request, unknownFactId));

        Long analysisId = createAnalysis();

        mockMvc.perform(asPm(get("/api/analyses/{id}", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.errorCode").value("PROVIDER_RESPONSE_INVALID"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM findings WHERE analysis_id = ?", Long.class, analysisId)).isZero();
    }

    @Test
    @DisplayName("Provider가 검색 결과에 없는 chunkId를 인용하면 결과를 저장하지 않는다")
    void unretrievedContextChunkReferenceIsRejected() throws Exception {
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        ReflectionTestUtils.setField(fake, "behavior",
                (Function<AnalysisRequest, AnalysisResult>) request ->
                        resultCitingChunks(List.of(Long.MAX_VALUE)));

        Long analysisId = createAnalysis();

        mockMvc.perform(asPm(get("/api/analyses/{id}", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.errorCode").value("PROVIDER_RESPONSE_INVALID"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM findings WHERE analysis_id = ?", Long.class, analysisId)).isZero();
    }

    @Test
    @DisplayName("Provider가 같은 검색 chunkId를 중복 인용하면 결과를 저장하지 않는다")
    void duplicateRetrievedContextChunkReferenceIsRejected() throws Exception {
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        ReflectionTestUtils.setField(fake, "behavior",
                (Function<AnalysisRequest, AnalysisResult>) request -> {
                    Long chunkId = request.retrievedContexts().getFirst().chunkId();
                    return resultCitingChunks(List.of(chunkId, chunkId));
                });

        Long analysisId = createAnalysis();

        mockMvc.perform(asPm(get("/api/analyses/{id}", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.errorCode").value("PROVIDER_RESPONSE_INVALID"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM findings WHERE analysis_id = ?", Long.class, analysisId)).isZero();
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

        mockMvc.perform(traced(withIdempotencyKey(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(documentId, List.of(1), List.of(1))))), traceId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DOCUMENT_NOT_CONFIRMED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("Persona 를 5개 선택하면 400 INVALID_SELECTION_COUNT")
    void invalidSelectionCount() throws Exception {
        String traceId = "analysis-invalid-selection";
        mockMvc.perform(traced(withIdempotencyKey(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(confirmedDocumentId, List.of(1), List.of(1, 2, 3, 4, 5))))), traceId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SELECTION_COUNT"));
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("Persona 4개 선택은 분석 요청에 포함되어 202로 수락된다")
    void acceptsFourPersonas() throws Exception {
        mockMvc.perform(withIdempotencyKey(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(confirmedDocumentId, List.of(1), List.of(1, 2, 3, 4))))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CREATED"));

        assertThat(((FakeRiskAnalysisProvider) provider).lastRequest().personaCodes()).hasSize(4);
    }

    @Test
    @DisplayName("존재하지 않는 근거 문서를 선택하면 400 INVALID_EVIDENCE_DOCUMENT")
    void invalidEvidenceDocument() throws Exception {
        String traceId = "analysis-invalid-evidence";
        mockMvc.perform(traced(withIdempotencyKey(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(confirmedDocumentId, List.of(999), List.of(1))))), traceId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_EVIDENCE_DOCUMENT"));
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("동일 입력으로 다시 요청하면 409 DUPLICATE_ANALYSIS_REQUEST")
    void duplicateRequest() throws Exception {
        createAnalysis();
        String traceId = "analysis-duplicate";

        mockMvc.perform(traced(withIdempotencyKey(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(confirmedDocumentId, List.of(1, 2), List.of(1, 2))))), traceId))
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
        assertSingleTerminalAudit(
                initialTraceId, "ANALYSIS_FAILED", analysisId, null, analysisId);
        assertAudit(traceId, "ANALYSIS_RETRIED", analysisId, 1L, analysisId);
        assertSingleTerminalAudit(traceId, "ANALYSIS_COMPLETED", analysisId, null, analysisId);
    }

    @Test
    @DisplayName("완료 감사 저장이 실패하면 완료 상태와 결과도 함께 롤백된다")
    void completedStateRollsBackWithTerminalAuditFailure() throws Exception {
        String traceId = "analysis-completed-audit-failure";
        failTerminalAuditPersistence(traceId);

        Long analysisId = createAnalysis(traceId);

        mockMvc.perform(asPm(get("/api/analyses/{id}", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM findings WHERE analysis_id = ?", Long.class, analysisId)).isZero();
        assertNoTerminalAudit(traceId);
    }

    @Test
    @DisplayName("실패 감사 저장이 실패하면 FAILED 상태도 함께 롤백된다")
    void failedStateRollsBackWithTerminalAuditFailure() throws Exception {
        String traceId = "analysis-failed-audit-failure";
        ((FakeRiskAnalysisProvider) provider).failWith(ErrorCode.AI_SERVICE_TEMPORARY_FAILURE, true);
        failTerminalAuditPersistence(traceId);

        Long analysisId = createAnalysis(traceId);

        mockMvc.perform(asPm(get("/api/analyses/{id}", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
        assertNoTerminalAudit(traceId);
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
        assertSingleTerminalAudit(
                failureTraceId, "ANALYSIS_FAILED", analysisId, null, analysisId);
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
        OffsetDateTime oldUpdatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM analyses WHERE id = ?", OffsetDateTime.class, analysisId);
        String oldExecutionToken = jdbc.queryForObject(
                "SELECT execution_token FROM analyses WHERE id = ?", String.class, analysisId);
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
            assertThat(jdbc.queryForObject(
                    "SELECT execution_token FROM analyses WHERE id = ?", String.class, analysisId))
                    .isNotEqualTo(oldExecutionToken);
            // updated_at 이 이전 실행과 같아져도 전용 token fence가 stale 결과를 차단해야 한다.
            jdbc.update("UPDATE analyses SET updated_at = ? WHERE id = ?", oldUpdatedAt, analysisId);
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
        assertSingleTerminalAudit(
                retryTraceId, "ANALYSIS_COMPLETED", analysisId, null, analysisId);
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

        mockMvc.perform(traced(withIdempotencyKey(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(othersDocumentId, List.of(1), List.of(1))))), traceId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_OWNERSHIP"));
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("검토자는 분석을 생성할 수 없다 (403)")
    void reviewerCannotCreate() throws Exception {
        String traceId = "analysis-create-wrong-role";
        mockMvc.perform(traced(withIdempotencyKey(asReviewer(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(confirmedDocumentId, List.of(1), List.of(1))))), traceId))
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
    @DisplayName("#46: 재시도는 Idempotency-Key 없이도 기존 잠금 판정으로 RUNNING을 거부한다")
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
