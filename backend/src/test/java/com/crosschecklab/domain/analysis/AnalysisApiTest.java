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

import com.crosschecklab.analysis.application.AnalysisJobService;
import com.crosschecklab.analysis.application.AnalysisRequestedEvent;
import com.crosschecklab.analysis.application.EvidenceRiskScorePolicyV1;
import com.crosschecklab.analysis.application.EvidenceRiskScoreService;
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
import com.crosschecklab.global.common.enums.PersonaCode;
import com.crosschecklab.global.common.enums.RedTeamRuleCode;
import com.crosschecklab.global.common.enums.Severity;
import com.crosschecklab.global.config.AsyncConfig;
import com.crosschecklab.global.error.ErrorCode;
import com.crosschecklab.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HexFormat;
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
    private static final String TEST_DOCUMENT_SOURCE_HASH = "d".repeat(64);
    private static final int LIMITED_PRODUCT_FAMILIARITY_PERSONA_ID = 6;
    private static final int LOSS_RECOVERY_PRESSURE_PERSONA_ID = 7;
    private static final int NEAR_TERM_LIQUIDITY_NEED_PERSONA_ID = 8;
    private static final int VARIABLE_CASH_FLOW_OR_REPAYMENT_CONSTRAINT_PERSONA_ID = 9;
    private static final int EXPLANATION_ACCESS_SUPPORT_PERSONA_ID = 10;
    private static final List<Integer> DEFAULT_PERSONA_IDS = List.of(
            LIMITED_PRODUCT_FAMILIARITY_PERSONA_ID,
            LOSS_RECOVERY_PRESSURE_PERSONA_ID);
    private static final List<Integer> FIVE_ACTIVE_PERSONA_IDS = List.of(
            LIMITED_PRODUCT_FAMILIARITY_PERSONA_ID,
            LOSS_RECOVERY_PRESSURE_PERSONA_ID,
            NEAR_TERM_LIQUIDITY_NEED_PERSONA_ID,
            VARIABLE_CASH_FLOW_OR_REPAYMENT_CONSTRAINT_PERSONA_ID,
            EXPLANATION_ACCESS_SUPPORT_PERSONA_ID);
    private static final List<PersonaCode> DEFAULT_PERSONA_CODES = List.of(
            PersonaCode.LIMITED_PRODUCT_FAMILIARITY,
            PersonaCode.LOSS_RECOVERY_PRESSURE);
    private static final List<PersonaCode> FIVE_ACTIVE_PERSONA_CODES = List.of(
            PersonaCode.LIMITED_PRODUCT_FAMILIARITY,
            PersonaCode.LOSS_RECOVERY_PRESSURE,
            PersonaCode.NEAR_TERM_LIQUIDITY_NEED,
            PersonaCode.VARIABLE_CASH_FLOW_OR_REPAYMENT_CONSTRAINT,
            PersonaCode.EXPLANATION_ACCESS_SUPPORT);
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
        EvidenceChunkIndexer testEvidenceChunkIndexer(JdbcTemplate jdbcTemplate,
                org.springframework.transaction.PlatformTransactionManager transactionManager) {
            return new TestEvidenceChunkIndexer(jdbcTemplate, transactionManager);
        }

        @Bean
        @Primary
        PgVectorEvidenceRetriever testEvidenceRetriever(JdbcTemplate jdbcTemplate) {
            return new TestEvidenceRetriever(jdbcTemplate);
        }
    }

    static class TestEvidenceChunkIndexer extends EvidenceChunkIndexer {

        private final JdbcTemplate jdbcTemplate;

        TestEvidenceChunkIndexer(JdbcTemplate jdbcTemplate,
                org.springframework.transaction.PlatformTransactionManager transactionManager) {
            super(jdbcTemplate, null, transactionManager);
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public IndexingResult indexSelected(Collection<Long> selectedEvidenceDocumentIds) {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()).isFalse();
            List<Long> selectedIds = normalizedSelectedIds(selectedEvidenceDocumentIds);
            for (Long evidenceDocumentId : selectedIds) {
                String chunkText = testContext(evidenceDocumentId);
                String sourceContent = jdbcTemplate.queryForObject(
                        "SELECT content FROM evidence_documents WHERE id = ? AND active = TRUE",
                        String.class,
                        evidenceDocumentId);
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
                        sha256(normalizeEvidenceContent(sourceContent)),
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
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()).isFalse();
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

    private static String testChunkHash(Long evidenceDocumentId) {
        return (evidenceDocumentId == 1L ? "a" : "b").repeat(64);
    }

    private static String normalizeEvidenceContent(String content) {
        return content.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
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
    private EvidenceRiskScoreService evidenceRiskScoreService;

    @Autowired
    private AnalysisJobService analysisJobService;

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
        useAnchoredDefaultResult();
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

    // append-only 실행 이력은 TRUNCATE CASCADE로 분석과 자식 행을 함께 비운다.
    private void clearFixtures() {
        jdbc.execute("TRUNCATE TABLE analysis_executions CASCADE");
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
        Long documentId = jdbc.queryForObject("""
                INSERT INTO product_documents
                    (product_id, file_name, media_type, file_size, checksum, storage_key,
                     extract_status, extracted_text, confirmed, created_at, updated_at)
                VALUES (?, '스마트인컴_상품설명서.pdf', 'application/pdf', 1, ?,
                        'mock://documents/guarantee', 'READY',
                        '최근 안정적인 수익률을 기록한 투자상품입니다.', ?, NOW(), NOW())
                RETURNING id""", Long.class, productId, TEST_DOCUMENT_SOURCE_HASH, confirmed);
        jdbc.update("""
                INSERT INTO document_source_revisions (
                    product_document_id, revision_number, file_name, media_type, file_size,
                    checksum, storage_key, source_hash)
                VALUES (?, 1, '스마트인컴_상품설명서.pdf', 'application/pdf', 1, ?,
                        'mock://documents/guarantee', ?)
                """, documentId, TEST_DOCUMENT_SOURCE_HASH, TEST_DOCUMENT_SOURCE_HASH);
        return documentId;
    }

    private Long createAnalysis() throws Exception {
        return createAnalysis(null);
    }

    private Long createAnalysis(String traceId) throws Exception {
        return createAnalysis(
                confirmedDocumentId, List.of(1, 2), DEFAULT_PERSONA_IDS, traceId);
    }

    private Long createAnalysis(
            Long documentId,
            List<Integer> evidenceIds,
            List<Integer> personaIds,
            String traceId
    ) throws Exception {
        MockHttpServletRequestBuilder builder = withIdempotencyKey(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(documentId, evidenceIds, personaIds))));
        if (traceId != null) {
            builder = traced(builder, traceId);
        }
        String body = mockMvc.perform(builder)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("analysisId").asLong();
    }

    private Long insertCreatedAnalysis(String executionToken, OffsetDateTime updatedAt) {
        AtomicReference<Long> analysisId = new AtomicReference<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Analysis analysis = Analysis.create(
                    confirmedDocumentId,
                    1L,
                    new LinkedHashSet<>(List.of(6L, 7L)),
                    new LinkedHashSet<>(List.of(1L, 2L)),
                    sha256("created-analysis-fixture:" + executionToken));
            analysisRepository.saveAndFlush(analysis);
            analysisId.set(analysis.getId());
        });
        jdbc.update("""
                UPDATE analyses
                SET execution_token = ?, updated_at = ?
                WHERE id = ?
                """, executionToken, updatedAt, analysisId.get());
        return analysisId.get();
    }

    private Long createReview(Long analysisId) throws Exception {
        String body = mockMvc.perform(asPm(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("analysisId", analysisId)))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("reviewId").asLong();
    }

    private void decideReview(Long reviewId, String decision, List<Long> findingIds) throws Exception {
        Map<String, Object> requestBody = "APPROVED".equals(decision)
                ? Map.of("status", decision, "selectedFindingIds", findingIds)
                : Map.of("status", decision, "comment", "정책 근거를 보완하세요.");
        mockMvc.perform(asReviewer(post("/api/reviews/{id}/decision", reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody))))
                .andExpect(status().isOk());
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
        return confirmFact(confirmedDocumentId, text, verificationStatus);
    }

    private Long confirmFact(Long documentId, String text, String verificationStatus) throws Exception {
        mockMvc.perform(asPm(patch("/api/documents/{documentId}/text", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("extractedText", text, "confirmed", true)))))
                .andExpect(status().isOk());

        String response = mockMvc.perform(asPm(get(
                        "/api/product-documents/{documentId}/ground-truth-facts", documentId)))
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
        AnalysisRequest.RetrievedContextPayload context = request.retrievedContexts().getFirst();
        return new AnalysisResult(82, "fact-aware-model", "fact-aware-prompt", List.of(new FindingPayload(
                "검증된 사실을 인용한 분석 결과입니다.",
                Severity.HIGH,
                RedTeamRuleCode.RETURN_FRAMING,
                DEFAULT_PERSONA_CODES,
                List.of(context.chunkId()),
                List.of(new FindingPayload.EvidenceSpanPayload(context.chunkId(), context.chunkText())),
                List.of(factId),
                "검증된 사실을 기준으로 설명하세요.")));
    }

    private AnalysisResult resultCitingChunks(
            List<Long> chunkIds, List<FindingPayload.EvidenceSpanPayload> spans) {
        return new AnalysisResult(82, "chunk-aware-model", "chunk-aware-prompt", List.of(new FindingPayload(
                "검색된 근거 청크를 인용한 분석 결과입니다.",
                Severity.HIGH,
                RedTeamRuleCode.RETURN_FRAMING,
                DEFAULT_PERSONA_CODES,
                chunkIds,
                spans,
                List.of(),
                "검색된 근거를 기준으로 설명하세요.")));
    }

    private AnalysisResult duplicateClaimResult(
            AnalysisRequest request,
            Long factId,
            boolean reverseOrder
    ) {
        AnalysisRequest.RetrievedContextPayload context = request.retrievedContexts().getFirst();
        FindingPayload concise = new FindingPayload(
                "비용이 누락되었습니다.",
                Severity.LOW,
                RedTeamRuleCode.COST_OMISSION,
                List.of(PersonaCode.LIMITED_PRODUCT_FAMILIARITY),
                List.of(context.chunkId()),
                List.of(new FindingPayload.EvidenceSpanPayload(context.chunkId(), context.chunkText())),
                List.of(factId),
                "비용을 명시하세요.");
        FindingPayload paraphrase = new FindingPayload(
                "동일한 문서 주장에 관한 비용 설명이 충분하지 않습니다.",
                Severity.HIGH,
                RedTeamRuleCode.COST_OMISSION,
                List.of(
                        PersonaCode.LOSS_RECOVERY_PRESSURE,
                        PersonaCode.LIMITED_PRODUCT_FAMILIARITY),
                List.of(context.chunkId()),
                List.of(new FindingPayload.EvidenceSpanPayload(context.chunkId(), context.chunkText())),
                List.of(factId),
                "같은 비용을 명확히 설명하세요.");
        return new AnalysisResult(
                99,
                "duplicate-claim-model",
                "duplicate-claim-prompt",
                reverseOrder ? List.of(paraphrase, concise) : List.of(concise, paraphrase));
    }

    private void useAnchoredDefaultResult() {
        useAnchoredResult(
                82,
                Severity.HIGH,
                "안정성 표현이 원금보장으로 오인될 가능성이 있습니다.");
    }

    private void useAnchoredResult(int riskScore, Severity severity, String statement) {
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        fake.reset();
        ReflectionTestUtils.setField(fake, "behavior",
                (Function<AnalysisRequest, AnalysisResult>) request -> {
                    AnalysisRequest.RetrievedContextPayload context =
                            request.retrievedContexts().getFirst();
                    return new AnalysisResult(riskScore, "mock-risk-v1", "mock-prompt-v1",
                            List.of(new FindingPayload(
                                    statement,
                                    severity,
                                    RedTeamRuleCode.STABILITY_KEYWORD,
                                    DEFAULT_PERSONA_CODES,
                                    List.of(context.chunkId()),
                                    List.of(new FindingPayload.EvidenceSpanPayload(
                                            context.chunkId(), context.chunkText())),
                                    List.of(),
                                    "안정성 표현과 같은 영역에 원금 손실 가능성을 명시하세요.")));
                });
    }

    @Test
    @DisplayName("ANA-001·004·#85: provider 점수는 provenance로만 남고 PENDING_REVIEW가 생성된다")
    void createAndComplete() throws Exception {
        String traceId = "analysis-create-success";
        mockMvc.perform(traced(withIdempotencyKey(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(confirmedDocumentId, List.of(1, 2), DEFAULT_PERSONA_IDS)))), traceId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.statusUrl").exists())
                .andExpect(jsonPath("$.resultUrl").exists());

        Long analysisId = jdbc.queryForObject("SELECT MAX(id) FROM analyses", Long.class);

        mockMvc.perform(asPm(get("/api/analyses/{id}", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.progress").value(100))
                .andExpect(jsonPath("$.riskScore").isEmpty())
                .andExpect(jsonPath("$.requiresHumanApproval").value(true))
                .andExpect(jsonPath("$.errorCode").isEmpty());

        mockMvc.perform(asPm(get("/api/analyses/{id}/result", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskScore").doesNotExist())
                .andExpect(jsonPath("$.score.state").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.score.policyVersion").value(EvidenceRiskScorePolicyV1.VERSION))
                .andExpect(jsonPath("$.score.value").isEmpty())
                .andExpect(jsonPath("$.score.notScoredReason").isEmpty())
                .andExpect(jsonPath("$.score.ledgerEntries").isEmpty())
                .andExpect(jsonPath("$.sourceDocument.fileName").value("스마트인컴_상품설명서.pdf"))
                .andExpect(jsonPath("$.groundingDocuments.length()").value(2))
                .andExpect(jsonPath("$.findings.length()").value(1))
                .andExpect(jsonPath("$.findings[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.findings[0].affectedPersonaCodes[0]")
                        .value("LIMITED_PRODUCT_FAMILIARITY"))
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
        Map<String, Object> execution = jdbc.queryForMap("""
                SELECT execution.id, execution.attempt_no, execution.execution_token,
                       execution.status, execution.retrieval_version,
                       execution.provider_risk_score, execution.model_version,
                       execution.prompt_version, analysis.current_successful_execution_id
                FROM analysis_executions execution
                JOIN analyses analysis ON analysis.id = execution.analysis_id
                WHERE analysis.id = ?
                """, analysisId);
        assertThat(execution)
                .containsEntry("attempt_no", 1)
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("retrieval_version", "pgvector-cosine-v1")
                .containsEntry("provider_risk_score", 82)
                .containsEntry("model_version", "mock-risk-v1")
                .containsEntry("prompt_version", "mock-prompt-v1")
                .containsEntry("current_successful_execution_id", execution.get("id"));
        assertThat(jdbc.queryForMap("""
                SELECT state, policy_version, score_value, not_scored_reason
                FROM risk_score_runs
                WHERE analysis_execution_id = ?
                """, execution.get("id")))
                .containsEntry("state", "PENDING_REVIEW")
                .containsEntry("policy_version", EvidenceRiskScorePolicyV1.VERSION)
                .containsEntry("score_value", null)
                .containsEntry("not_scored_reason", null);
        assertThat(execution.get("execution_token")).isEqualTo(jdbc.queryForObject(
                "SELECT execution_token FROM analyses WHERE id = ?", String.class, analysisId));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM analysis_rag_runs rag_run
                JOIN analysis_executions execution
                  ON execution.id = rag_run.analysis_execution_id
                WHERE execution.analysis_id = ?
                """, Long.class, analysisId)).isEqualTo(1L);
        Map<String, Object> anchoredFinding = jdbc.queryForMap("""
                SELECT f.analysis_execution_id, f.lineage_id, f.revision_number,
                       anchor.source_role, anchor.evidence_document_id,
                       anchor.retrieved_chunk_id, anchor.page_number,
                       anchor.utf8_end_offset - anchor.utf8_start_offset AS excerpt_bytes,
                       anchor.exact_excerpt
                FROM findings f
                JOIN finding_evidence_anchors anchor ON anchor.finding_id = f.id
                WHERE f.analysis_id = ?
                """, analysisId);
        assertThat(anchoredFinding)
                .containsEntry("analysis_execution_id", execution.get("id"))
                .containsEntry("revision_number", 1)
                .containsEntry("source_role", "POLICY_REQUIREMENT")
                .containsEntry("evidence_document_id", 1L)
                .containsEntry("page_number", 1)
                .containsEntry("exact_excerpt", TEST_RETRIEVED_CONTEXTS.get(1L));
        assertThat(anchoredFinding.get("lineage_id").toString())
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        assertThat(((Number) anchoredFinding.get("excerpt_bytes")).longValue())
                .isEqualTo(TEST_RETRIEVED_CONTEXTS.get(1L).getBytes(StandardCharsets.UTF_8).length);

        assertAudit(traceId, "ANALYSIS_CREATED", analysisId, 1L, analysisId);
        assertSingleTerminalAudit(traceId, "ANALYSIS_COMPLETED", analysisId, null, analysisId);
    }

    @Test
    @DisplayName("Finding이 없는 clean provider 결과는 null provenance 점수로 완료되고 검토 대기한다")
    void zeroFindingsCompletePendingReviewWithoutFakeProviderScore() throws Exception {
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        ReflectionTestUtils.setField(fake, "behavior",
                (Function<AnalysisRequest, AnalysisResult>) request ->
                        new AnalysisResult(null, "clean-model", "clean-prompt", List.of()));

        Long analysisId = createAnalysis();
        Long executionId = jdbc.queryForObject(
                "SELECT current_successful_execution_id FROM analyses WHERE id = ?",
                Long.class,
                analysisId);

        assertThat(jdbc.queryForMap("""
                SELECT status, provider_risk_score, model_version, prompt_version
                FROM analysis_executions
                WHERE id = ?
                """, executionId))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("provider_risk_score", null)
                .containsEntry("model_version", "clean-model")
                .containsEntry("prompt_version", "clean-prompt");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM findings WHERE analysis_execution_id = ?",
                Long.class,
                executionId)).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT state, policy_version, score_value, not_scored_reason
                FROM risk_score_runs
                WHERE analysis_execution_id = ?
                """, executionId))
                .containsEntry("state", "PENDING_REVIEW")
                .containsEntry("policy_version", EvidenceRiskScorePolicyV1.VERSION)
                .containsEntry("score_value", null)
                .containsEntry("not_scored_reason", null);
        mockMvc.perform(asPm(get("/api/analyses/{id}/result", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskScore").doesNotExist())
                .andExpect(jsonPath("$.score.state").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.score.value").isEmpty())
                .andExpect(jsonPath("$.findings").isEmpty());
    }

    @Test
    @DisplayName("#85: 여러 policy anchor의 입력 순서와 무관하게 점수와 canonical ledger anchor를 결정한다")
    void approvedAnchoredFindingCreatesIdempotentDeterministicScore() throws Exception {
        Long factId = confirmFact("정책에 비추어 검토할 확정 문서 주장", "VERIFIED");
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        ReflectionTestUtils.setField(fake, "behavior",
                (Function<AnalysisRequest, AnalysisResult>) request -> {
                    AnalysisRequest.RetrievedContextPayload first = request.retrievedContexts().getFirst();
                    AnalysisRequest.RetrievedContextPayload second = request.retrievedContexts().getLast();
                    return new AnalysisResult(
                            82,
                            "fact-aware-model",
                            "fact-aware-prompt",
                            List.of(new FindingPayload(
                                    "검증된 사실을 인용한 분석 결과입니다.",
                                    Severity.HIGH,
                                    RedTeamRuleCode.RETURN_FRAMING,
                                    DEFAULT_PERSONA_CODES,
                                    List.of(second.chunkId(), first.chunkId()),
                                    List.of(
                                            new FindingPayload.EvidenceSpanPayload(
                                                    second.chunkId(), second.chunkText()),
                                            new FindingPayload.EvidenceSpanPayload(
                                                    first.chunkId(), first.chunkText())),
                                    List.of(factId),
                                    "검증된 사실을 기준으로 설명하세요.")));
                });

        Long analysisId = createAnalysis();
        Long executionId = jdbc.queryForObject(
                "SELECT current_successful_execution_id FROM analyses WHERE id = ?",
                Long.class,
                analysisId);
        Long findingId = jdbc.queryForObject(
                "SELECT id FROM findings WHERE analysis_execution_id = ?", Long.class, executionId);
        List<Map<String, Object>> anchors = jdbc.queryForList("""
                SELECT id, source_role, source_document_id, source_revision_id, evidence_document_id,
                       retrieved_chunk_id, source_hash, page_number, utf8_start_offset,
                       utf8_end_offset, excerpt_hash, exact_excerpt
                FROM finding_evidence_anchors
                WHERE finding_id = ?
                ORDER BY source_role, id
                """, findingId);
        assertThat(anchors)
                .extracting(
                        anchor -> anchor.get("source_role"),
                        anchor -> anchor.get("source_document_id"),
                        anchor -> anchor.get("evidence_document_id"))
                .containsExactly(
                        tuple("DOCUMENT_CLAIM", confirmedDocumentId, null),
                        tuple("POLICY_REQUIREMENT", null, 2L),
                        tuple("POLICY_REQUIREMENT", null, 1L));
        assertThat(anchors.subList(1, anchors.size()))
                .extracting(anchor -> anchor.get("exact_excerpt"))
                .containsExactly(
                        TEST_RETRIEVED_CONTEXTS.get(2L),
                        TEST_RETRIEVED_CONTEXTS.get(1L));
        Map<String, Object> documentClaim = anchors.getFirst();
        assertThat(documentClaim)
                .containsEntry("source_hash", TEST_DOCUMENT_SOURCE_HASH)
                .containsEntry("page_number", 1)
                .containsEntry("utf8_start_offset", 0L)
                .containsEntry(
                        "utf8_end_offset",
                        (long) "정책에 비추어 검토할 확정 문서 주장".getBytes(StandardCharsets.UTF_8).length)
                .containsEntry("excerpt_hash", sha256("정책에 비추어 검토할 확정 문서 주장"))
                .containsEntry("exact_excerpt", "정책에 비추어 검토할 확정 문서 주장");
        assertThat(documentClaim.get("source_revision_id")).isEqualTo(jdbc.queryForObject("""
                SELECT id FROM document_source_revisions
                WHERE product_document_id = ? AND source_hash = ?
                """, Long.class, confirmedDocumentId, TEST_DOCUMENT_SOURCE_HASH));
        Long reviewId = createReview(analysisId);
        decideReview(reviewId, "APPROVED", List.of(findingId));

        Map<String, Object> scored = jdbc.queryForMap("""
                SELECT id, state, score_value, policy_version, input_fingerprint
                FROM risk_score_runs
                WHERE analysis_execution_id = ? AND state = 'SCORED'
                """, executionId);
        assertThat(scored)
                .containsEntry("state", "SCORED")
                .containsEntry("score_value", 64)
                .containsEntry("policy_version", EvidenceRiskScorePolicyV1.VERSION);
        assertThat(scored.get("input_fingerprint").toString()).matches("[0-9a-f]{64}");
        assertThat(jdbc.queryForMap("""
                SELECT policy_rule_id, magnitude_basis_points, likelihood_basis_points,
                       contribution_basis_points
                FROM risk_score_ledger_entries
                WHERE risk_score_run_id = ?
                """, scored.get("id")))
                .containsEntry("policy_rule_id", "RETURN_FRAMING")
                .containsEntry("magnitude_basis_points", 8000)
                .containsEntry("likelihood_basis_points", 8000)
                .containsEntry("contribution_basis_points", 6400);
        Map<String, Object> anchorIds = jdbc.queryForMap("""
                SELECT document_claim_anchor_id, policy_requirement_anchor_id
                FROM risk_score_ledger_entries
                WHERE risk_score_run_id = ?
                """, scored.get("id"));
        Long canonicalPolicyAnchorId = anchors.stream()
                .filter(anchor -> "POLICY_REQUIREMENT".equals(anchor.get("source_role")))
                .map(anchor -> ((Number) anchor.get("id")).longValue())
                .min(Long::compareTo)
                .orElseThrow();
        assertThat(((Number) anchorIds.get("policy_requirement_anchor_id")).longValue())
                .isEqualTo(canonicalPolicyAnchorId);
        mockMvc.perform(asPm(get("/api/analyses/{id}/result", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskScore").doesNotExist())
                .andExpect(jsonPath("$.score.state").value("SCORED"))
                .andExpect(jsonPath("$.score.policyVersion").value(EvidenceRiskScorePolicyV1.VERSION))
                .andExpect(jsonPath("$.score.value").value(64))
                .andExpect(jsonPath("$.score.notScoredReason").isEmpty())
                .andExpect(jsonPath("$.score.ledgerEntries.length()").value(1))
                .andExpect(jsonPath("$.score.ledgerEntries[0].findingId").value(findingId))
                .andExpect(jsonPath("$.score.ledgerEntries[0].policyRuleCode").value("RETURN_FRAMING"))
                .andExpect(jsonPath("$.score.ledgerEntries[0].magnitudeBasisPoints").value(8000))
                .andExpect(jsonPath("$.score.ledgerEntries[0].likelihoodBasisPoints").value(8000))
                .andExpect(jsonPath("$.score.ledgerEntries[0].contributionBasisPoints").value(6400))
                .andExpect(jsonPath("$.score.ledgerEntries[0].documentClaimAnchorId")
                        .value(((Number) anchorIds.get("document_claim_anchor_id")).longValue()))
                .andExpect(jsonPath("$.score.ledgerEntries[0].policyRequirementAnchorId")
                        .value(((Number) anchorIds.get("policy_requirement_anchor_id")).longValue()));

        Long firstRunId = ((Number) scored.get("id")).longValue();
        assertThat(evidenceRiskScoreService.scoreAfterReview(reviewId).getId()).isEqualTo(firstRunId);
        assertThat(evidenceRiskScoreService.scoreAfterReview(reviewId).getId()).isEqualTo(firstRunId);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM risk_score_runs
                WHERE analysis_execution_id = ? AND state = 'SCORED'
                """, Long.class, executionId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT risk_score FROM analyses WHERE id = ?", Integer.class, analysisId))
                .isEqualTo(64);
    }

    @Test
    @DisplayName("같은 source claim과 rule의 중복·paraphrase는 Finding을 보존하되 한 번만 점수화한다")
    void duplicateSameClaimAndRuleCountsOnceRegardlessOfFindingOrder() throws Exception {
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;

        List<Long> analysisIds = new java.util.ArrayList<>();
        for (boolean reverseOrder : List.of(false, true)) {
            Long documentId = insertDocument(true);
            Long factId = confirmFact(
                    documentId,
                    "총비용은 상품 설명에서 명확히 고지되어야 합니다.",
                    "VERIFIED");
            ReflectionTestUtils.setField(fake, "behavior",
                    (Function<AnalysisRequest, AnalysisResult>) request ->
                            duplicateClaimResult(request, factId, reverseOrder));
            Long analysisId = createAnalysis(
                    documentId,
                    List.of(1),
                    DEFAULT_PERSONA_IDS,
                    null);
            List<Long> findingIds = jdbc.queryForList("""
                    SELECT id FROM findings
                    WHERE analysis_id = ?
                    ORDER BY id
                    """, Long.class, analysisId);
            assertThat(findingIds).hasSize(2);
            decideReview(createReview(analysisId), "APPROVED", findingIds);
            analysisIds.add(analysisId);

            Long executionId = jdbc.queryForObject(
                    "SELECT current_successful_execution_id FROM analyses WHERE id = ?",
                    Long.class,
                    analysisId);
            Map<String, Object> run = jdbc.queryForMap("""
                    SELECT id, score_value FROM risk_score_runs
                    WHERE analysis_execution_id = ? AND state = 'SCORED'
                    """, executionId);
            assertThat(run).containsEntry("score_value", 64);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM risk_score_ledger_entries
                    WHERE risk_score_run_id = ?
                    """, Long.class, run.get("id"))).isEqualTo(1L);
            assertThat(jdbc.queryForObject("""
                    SELECT finding_revision_id FROM risk_score_ledger_entries
                    WHERE risk_score_run_id = ?
                    """, Long.class, run.get("id"))).isEqualTo(findingIds.getFirst());
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM finding_review_decisions decision
                    JOIN reviews review ON review.id = decision.review_id
                    WHERE review.analysis_id = ?
                      AND decision.decision = 'APPROVED'
                    """, Long.class, analysisId)).isEqualTo(2L);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM finding_evidence_anchors anchor
                    JOIN findings finding ON finding.id = anchor.finding_id
                    WHERE finding.analysis_id = ?
                    """, Long.class, analysisId)).isEqualTo(4L);
        }

        assertThat(jdbc.queryForList("""
                SELECT run.score_value
                FROM risk_score_runs run
                JOIN analysis_executions execution ON execution.id = run.analysis_execution_id
                WHERE execution.analysis_id IN (?, ?) AND run.state = 'SCORED'
                ORDER BY execution.analysis_id
                """, Integer.class, analysisIds.get(0), analysisIds.get(1)))
                .containsExactly(64, 64);
    }

    @Test
    @DisplayName("같은 source claim이어도 서로 다른 rule은 각각 독립된 위험으로 점수화한다")
    void differentRulesOnSameClaimCountSeparately() throws Exception {
        Long factId = confirmFact("환매 조건과 비용을 함께 명확히 고지합니다.", "VERIFIED");
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        ReflectionTestUtils.setField(fake, "behavior",
                (Function<AnalysisRequest, AnalysisResult>) request -> {
                    AnalysisRequest.RetrievedContextPayload context =
                            request.retrievedContexts().getFirst();
                    return new AnalysisResult(1, "different-rule-model", "different-rule-prompt", List.of(
                            new FindingPayload(
                                    "환매 가능성 표현을 바로잡아야 합니다.",
                                    Severity.LOW,
                                    RedTeamRuleCode.RETURN_FRAMING,
                                    List.of(PersonaCode.LIMITED_PRODUCT_FAMILIARITY),
                                    List.of(context.chunkId()),
                                    List.of(new FindingPayload.EvidenceSpanPayload(
                                            context.chunkId(), context.chunkText())),
                                    List.of(factId),
                                    "환매 조건을 명시하세요."),
                            new FindingPayload(
                                    "같은 문장의 비용 고지가 불충분합니다.",
                                    Severity.HIGH,
                                    RedTeamRuleCode.COST_OMISSION,
                                    DEFAULT_PERSONA_CODES,
                                    List.of(context.chunkId()),
                                    List.of(new FindingPayload.EvidenceSpanPayload(
                                            context.chunkId(), context.chunkText())),
                                    List.of(factId),
                                    "비용을 명시하세요.")));
                });

        Long analysisId = createAnalysis(
                confirmedDocumentId,
                List.of(1),
                DEFAULT_PERSONA_IDS,
                null);
        List<Long> findingIds = jdbc.queryForList(
                "SELECT id FROM findings WHERE analysis_id = ? ORDER BY id",
                Long.class,
                analysisId);
        decideReview(createReview(analysisId), "APPROVED", findingIds);
        Long executionId = jdbc.queryForObject(
                "SELECT current_successful_execution_id FROM analyses WHERE id = ?",
                Long.class,
                analysisId);
        Map<String, Object> run = jdbc.queryForMap("""
                SELECT id, score_value FROM risk_score_runs
                WHERE analysis_execution_id = ? AND state = 'SCORED'
                """, executionId);

        assertThat(run).containsEntry("score_value", 87);
        assertThat(jdbc.queryForList("""
                SELECT policy_rule_id FROM risk_score_ledger_entries
                WHERE risk_score_run_id = ?
                ORDER BY finding_revision_id
                """, String.class, run.get("id")))
                .containsExactly("RETURN_FRAMING", "COST_OMISSION");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM risk_score_ledger_entries
                WHERE risk_score_run_id = ?
                """, Long.class, run.get("id"))).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM findings WHERE analysis_id = ?",
                Long.class,
                analysisId)).isEqualTo(2L);
    }

    @Test
    @DisplayName("#85: noisy-OR는 순서 불변이고 중간 반올림 없이 마지막에만 반올림한다")
    void policyNoisyOrHasStableScoreInvariants() {
        assertThat(EvidenceRiskScorePolicyV1.VERSION).isEqualTo("1.1.0");
        assertThat(EvidenceRiskScorePolicyV1.components(RedTeamRuleCode.RETURN_FRAMING))
                .isEqualTo(new EvidenceRiskScorePolicyV1.Components(8000, 8000));
        assertThat(EvidenceRiskScorePolicyV1.components(RedTeamRuleCode.LOSS_SOFTENING))
                .isEqualTo(new EvidenceRiskScorePolicyV1.Components(10_000, 8000));
        assertThat(EvidenceRiskScorePolicyV1.components(RedTeamRuleCode.COST_OMISSION))
                .isEqualTo(new EvidenceRiskScorePolicyV1.Components(8000, 8000));
        assertThat(EvidenceRiskScorePolicyV1.components(RedTeamRuleCode.STABILITY_KEYWORD))
                .isEqualTo(new EvidenceRiskScorePolicyV1.Components(10_000, 10_000));
        assertThat(EvidenceRiskScorePolicyV1.components(RedTeamRuleCode.FORMAL_CONFIRMATION))
                .isEqualTo(new EvidenceRiskScorePolicyV1.Components(6000, 6000));
        assertThat(EvidenceRiskScorePolicyV1.components(RedTeamRuleCode.COGNITIVE_ACCESSIBILITY))
                .isEqualTo(new EvidenceRiskScorePolicyV1.Components(6000, 6000));
        assertThat(EvidenceRiskScorePolicyV1.aggregate(List.of(6400, 8000, 3600)))
                .isEqualTo(EvidenceRiskScorePolicyV1.aggregate(List.of(3600, 6400, 8000)))
                .isEqualTo(95);
        assertThat(EvidenceRiskScorePolicyV1.aggregate(List.of(6400))).isEqualTo(64);
        assertThat(EvidenceRiskScorePolicyV1.aggregate(List.of(6400, 8000))).isEqualTo(93);
        assertThat(EvidenceRiskScorePolicyV1.aggregate(List.of(10_000))).isEqualTo(100);
    }

    @Test
    @DisplayName("#85: provider 점수·severity·persona·similarity·원문이 달라도 같은 rule은 같은 점수다")
    void prohibitedProviderInputsDoNotChangePolicyScore() throws Exception {
        Long firstFactId = confirmFact("첫 번째 확정 주장", "VERIFIED");
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        ReflectionTestUtils.setField(fake, "behavior",
                (Function<AnalysisRequest, AnalysisResult>) request -> {
                    AnalysisRequest.RetrievedContextPayload context =
                            request.retrievedContexts().getFirst();
                    return new AnalysisResult(3, "model-a", "prompt-a", List.of(new FindingPayload(
                            "낮은 severity의 첫 원문",
                            Severity.LOW,
                            RedTeamRuleCode.RETURN_FRAMING,
                            List.of(PersonaCode.LIMITED_PRODUCT_FAMILIARITY),
                            List.of(context.chunkId()),
                            List.of(new FindingPayload.EvidenceSpanPayload(
                                    context.chunkId(), context.chunkText())),
                            List.of(firstFactId),
                            "첫 권고")));
                });
        Long firstAnalysisId = createAnalysis(
                confirmedDocumentId,
                List.of(1),
                List.of(LIMITED_PRODUCT_FAMILIARITY_PERSONA_ID),
                null);

        Long secondDocumentId = insertDocument(true);
        Long secondFactId = confirmFact(secondDocumentId, "두 번째 확정 주장은 원문도 다름", "VERIFIED");
        ReflectionTestUtils.setField(fake, "behavior",
                (Function<AnalysisRequest, AnalysisResult>) request -> {
                    AnalysisRequest.RetrievedContextPayload context =
                            request.retrievedContexts().getLast();
                    return new AnalysisResult(99, "model-b", "prompt-b", List.of(new FindingPayload(
                            "높은 severity의 완전히 다른 원문",
                            Severity.HIGH,
                            RedTeamRuleCode.RETURN_FRAMING,
                            List.of(
                                    PersonaCode.LOSS_RECOVERY_PRESSURE,
                                    PersonaCode.LIMITED_PRODUCT_FAMILIARITY),
                            List.of(context.chunkId()),
                            List.of(new FindingPayload.EvidenceSpanPayload(
                                    context.chunkId(), context.chunkText())),
                            List.of(secondFactId),
                            "다른 권고")));
                });
        Long secondAnalysisId = createAnalysis(
                secondDocumentId,
                List.of(1, 2),
                List.of(LOSS_RECOVERY_PRESSURE_PERSONA_ID, LIMITED_PRODUCT_FAMILIARITY_PERSONA_ID),
                null);

        for (Long analysisId : List.of(firstAnalysisId, secondAnalysisId)) {
            Long executionId = jdbc.queryForObject(
                    "SELECT current_successful_execution_id FROM analyses WHERE id = ?",
                    Long.class,
                    analysisId);
            Long findingId = jdbc.queryForObject(
                    "SELECT id FROM findings WHERE analysis_execution_id = ?", Long.class, executionId);
            decideReview(createReview(analysisId), "APPROVED", List.of(findingId));
        }

        assertThat(jdbc.queryForList("""
                SELECT run.score_value
                FROM risk_score_runs run
                JOIN analysis_executions execution ON execution.id = run.analysis_execution_id
                WHERE execution.analysis_id IN (?, ?) AND run.state = 'SCORED'
                ORDER BY execution.analysis_id
                """, Integer.class, firstAnalysisId, secondAnalysisId))
                .containsExactly(64, 64);
        assertThat(jdbc.queryForList("""
                SELECT provider_risk_score
                FROM analysis_executions
                WHERE analysis_id IN (?, ?)
                ORDER BY analysis_id
                """, Integer.class, firstAnalysisId, secondAnalysisId))
                .containsExactly(3, 99);
    }

    @Test
    @DisplayName("#85: reviewer 반려는 숫자 0 대신 NOT_SCORED를 만든다")
    void rejectedReviewCreatesNotScored() throws Exception {
        Long analysisId = createAnalysis();
        Long executionId = jdbc.queryForObject(
                "SELECT current_successful_execution_id FROM analyses WHERE id = ?",
                Long.class,
                analysisId);
        Long reviewId = createReview(analysisId);
        decideReview(reviewId, "REJECTED", List.of());

        assertThat(jdbc.queryForMap("""
                SELECT state, score_value, not_scored_reason
                FROM risk_score_runs
                WHERE analysis_execution_id = ? AND state = 'NOT_SCORED'
                """, executionId))
                .containsEntry("state", "NOT_SCORED")
                .containsEntry("score_value", null)
                .containsEntry("not_scored_reason", "REVIEW_REJECTED");
        mockMvc.perform(asPm(get("/api/analyses/{id}/result", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score.state").value("NOT_SCORED"))
                .andExpect(jsonPath("$.score.policyVersion").value(EvidenceRiskScorePolicyV1.VERSION))
                .andExpect(jsonPath("$.score.value").isEmpty())
                .andExpect(jsonPath("$.score.notScoredReason").value("REVIEW_REJECTED"))
                .andExpect(jsonPath("$.score.ledgerEntries").isEmpty());
    }

    @Test
    @DisplayName("#85: 승인 Finding의 source anchor가 없으면 0점이 아니라 NOT_SCORED다")
    void approvedFindingMissingRequiredAnchorIsNotScored() throws Exception {
        Long analysisId = createAnalysis();
        Long executionId = jdbc.queryForObject(
                "SELECT current_successful_execution_id FROM analyses WHERE id = ?",
                Long.class,
                analysisId);
        Long findingId = jdbc.queryForObject(
                "SELECT id FROM findings WHERE analysis_execution_id = ?", Long.class, executionId);
        Long reviewId = createReview(analysisId);
        decideReview(reviewId, "APPROVED", List.of(findingId));

        assertThat(jdbc.queryForMap("""
                SELECT state, score_value, not_scored_reason
                FROM risk_score_runs
                WHERE analysis_execution_id = ? AND state = 'NOT_SCORED'
                """, executionId))
                .containsEntry("state", "NOT_SCORED")
                .containsEntry("score_value", null)
                .containsEntry("not_scored_reason", "REQUIRED_ANCHOR_CARDINALITY");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM risk_score_ledger_entries ledger
                JOIN risk_score_runs run ON run.id = ledger.risk_score_run_id
                WHERE run.analysis_execution_id = ?
                """, Long.class, executionId)).isZero();
        mockMvc.perform(asPm(get("/api/analyses/{id}/result", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score.state").value("NOT_SCORED"))
                .andExpect(jsonPath("$.score.value").isEmpty())
                .andExpect(jsonPath("$.score.notScoredReason").value("REQUIRED_ANCHOR_CARDINALITY"))
                .andExpect(jsonPath("$.score.ledgerEntries").isEmpty());
    }

    @Test
    @DisplayName("#85: cited fact가 원문에서 모호하면 claim anchor를 만들지 않고 NOT_SCORED다")
    void approvedFindingWithAmbiguousFactAnchorIsNotScored() throws Exception {
        String sourceText = "반복된 문서 주장 / 반복된 문서 주장";
        Long factId = confirmFact(sourceText, "CANDIDATE");
        jdbc.update("""
                UPDATE ground_truth_facts
                SET value = '반복된 문서 주장',
                    verification_status = 'VERIFIED',
                    decided_by = 1,
                    decided_at = NOW()
                WHERE id = ?
                """, factId);
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        ReflectionTestUtils.setField(fake, "behavior",
                (Function<AnalysisRequest, AnalysisResult>) request -> resultCiting(request, factId));

        Long analysisId = createAnalysis();
        Long executionId = jdbc.queryForObject(
                "SELECT current_successful_execution_id FROM analyses WHERE id = ?",
                Long.class,
                analysisId);
        Long findingId = jdbc.queryForObject(
                "SELECT id FROM findings WHERE analysis_execution_id = ?", Long.class, executionId);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM finding_evidence_anchors
                WHERE finding_id = ? AND source_role = 'DOCUMENT_CLAIM'
                """, Long.class, findingId)).isZero();
        Long reviewId = createReview(analysisId);
        decideReview(reviewId, "APPROVED", List.of(findingId));

        assertThat(jdbc.queryForMap("""
                SELECT state, score_value, not_scored_reason
                FROM risk_score_runs
                WHERE analysis_execution_id = ? AND state = 'NOT_SCORED'
                """, executionId))
                .containsEntry("state", "NOT_SCORED")
                .containsEntry("score_value", null)
                .containsEntry("not_scored_reason", "REQUIRED_ANCHOR_CARDINALITY");
    }

    @Test
    @DisplayName("#46: 같은 키와 정규화된 같은 요청은 최초 CREATED 응답을 재생하고 부수 효과를 반복하지 않는다")
    void sameIdempotencyKeyReplaysCreatedResponse() throws Exception {
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        AtomicInteger providerCalls = new AtomicInteger();
        ReflectionTestUtils.setField(fake, "behavior",
                (Function<AnalysisRequest, AnalysisResult>) request -> {
                    providerCalls.incrementAndGet();
                    return new AnalysisResult(null, "idempotency-model", "idempotency-prompt", List.of());
                });
        String key = "analysis-replay-key";
        String firstTraceId = "analysis-replay-first";
        String replayTraceId = "analysis-replay-second";

        String firstBody = mockMvc.perform(traced(withIdempotencyKey(asPm(post("/api/analyses")
                                .header("X-Demo-Scenario", "  ORIGINAL_SCENARIO  ")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request(
                                        confirmedDocumentId,
                                        List.of(2, 1, 2),
                                        List.of(
                                                LOSS_RECOVERY_PRESSURE_PERSONA_ID,
                                                LIMITED_PRODUCT_FAMILIARITY_PERSONA_ID,
                                                LOSS_RECOVERY_PRESSURE_PERSONA_ID)))),
                        key), firstTraceId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();

        // 재생 경로가 현재 문서 상태를 다시 검증하지 않는지 확인한다.
        jdbc.update("UPDATE product_documents SET confirmed = FALSE WHERE id = ?", confirmedDocumentId);
        String replayBody = mockMvc.perform(traced(withIdempotencyKey(asPm(post("/api/analyses")
                                .header("X-Demo-Scenario", "ORIGINAL_SCENARIO")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request(confirmedDocumentId, List.of(1, 2), DEFAULT_PERSONA_IDS))),
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
                        .content(request(
                                confirmedDocumentId,
                                List.of(1),
                                List.of(LIMITED_PRODUCT_FAMILIARITY_PERSONA_ID)))), key))
                .andExpect(status().isAccepted());

        String traceId = "analysis-key-reused";
        mockMvc.perform(traced(withIdempotencyKey(asPm(post("/api/analyses")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request(
                                        confirmedDocumentId,
                                        List.of(2),
                                        List.of(LIMITED_PRODUCT_FAMILIARITY_PERSONA_ID)))),
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
                    return new AnalysisResult(null, "concurrent-model", "concurrent-prompt", List.of());
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
                                                confirmedDocumentId, List.of(1, 2), DEFAULT_PERSONA_IDS))),
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
                        .content(request(
                                confirmedDocumentId,
                                List.of(1),
                                List.of(LIMITED_PRODUCT_FAMILIARITY_PERSONA_ID)))), key))
                .andExpect(status().isAccepted());

        mockMvc.perform(withIdempotencyKey(asReviewer(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(
                                confirmedDocumentId,
                                List.of(1),
                                List.of(LIMITED_PRODUCT_FAMILIARITY_PERSONA_ID)))), key))
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
        String body = request(
                confirmedDocumentId,
                List.of(1),
                List.of(LIMITED_PRODUCT_FAMILIARITY_PERSONA_ID));

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
            return new AnalysisResult(null, "trace-test", "trace-test", List.of());
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
        assertThat(request.personaCodes()).containsExactlyElementsOf(DEFAULT_PERSONA_CODES);
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
        assertThat(jdbc.queryForMap("""
                SELECT anchor.source_role, anchor.source_document_id, anchor.source_revision_id,
                       anchor.source_hash, anchor.page_number, anchor.utf8_start_offset,
                       anchor.utf8_end_offset, anchor.exact_excerpt
                FROM finding_evidence_anchors anchor
                JOIN findings finding ON finding.id = anchor.finding_id
                WHERE finding.analysis_id = ? AND anchor.source_role = 'DOCUMENT_CLAIM'
                """, analysisId))
                .containsEntry("source_role", "DOCUMENT_CLAIM")
                .containsEntry("source_document_id", confirmedDocumentId)
                .containsEntry("source_hash", TEST_DOCUMENT_SOURCE_HASH)
                .containsEntry("page_number", 1)
                .containsEntry("utf8_start_offset", 0L)
                .containsEntry("exact_excerpt", "Provider가 인용할 검증된 사실");
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
    @DisplayName("Provider가 요청에서 선택하지 않은 persona를 반환하면 결과를 저장하지 않는다")
    void unselectedPersonaReferenceIsRejected() throws Exception {
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        ReflectionTestUtils.setField(fake, "behavior",
                (Function<AnalysisRequest, AnalysisResult>) request -> {
                    AnalysisRequest.RetrievedContextPayload context = request.retrievedContexts().getFirst();
                    return new AnalysisResult(82, "mock-risk-v1", "mock-prompt-v1", List.of(new FindingPayload(
                            "선택하지 않은 persona를 인용한 결과입니다.",
                            Severity.HIGH,
                            RedTeamRuleCode.STABILITY_KEYWORD,
                            List.of(PersonaCode.LIMITED_PRODUCT_FAMILIARITY, PersonaCode.SENIOR),
                            List.of(context.chunkId()),
                            List.of(new FindingPayload.EvidenceSpanPayload(
                                    context.chunkId(), context.chunkText())),
                            List.of(),
                            "선택 persona 범위 안에서만 설명하세요.")));
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
    @DisplayName("Provider가 빈 persona 인용 목록을 반환하면 결과를 저장하지 않는다")
    void emptyPersonaReferenceIsRejected() throws Exception {
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        ReflectionTestUtils.setField(fake, "behavior",
                (Function<AnalysisRequest, AnalysisResult>) request -> {
                    AnalysisRequest.RetrievedContextPayload context = request.retrievedContexts().getFirst();
                    return new AnalysisResult(82, "mock-risk-v1", "mock-prompt-v1", List.of(new FindingPayload(
                            "persona 인용 목록이 비어 있는 결과입니다.",
                            Severity.HIGH,
                            RedTeamRuleCode.STABILITY_KEYWORD,
                            List.of(),
                            List.of(context.chunkId()),
                            List.of(new FindingPayload.EvidenceSpanPayload(
                                    context.chunkId(), context.chunkText())),
                            List.of(),
                            "persona를 선택해 설명하세요.")));
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
    @DisplayName("Provider가 중복 persona를 인용하면 결과를 저장하지 않는다")
    void duplicatePersonaReferenceIsRejected() throws Exception {
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        ReflectionTestUtils.setField(fake, "behavior",
                (Function<AnalysisRequest, AnalysisResult>) request -> {
                    AnalysisRequest.RetrievedContextPayload context = request.retrievedContexts().getFirst();
                    return new AnalysisResult(82, "mock-risk-v1", "mock-prompt-v1", List.of(new FindingPayload(
                            "persona를 중복 인용한 결과입니다.",
                            Severity.HIGH,
                            RedTeamRuleCode.STABILITY_KEYWORD,
                            List.of(
                                    PersonaCode.LIMITED_PRODUCT_FAMILIARITY,
                                    PersonaCode.LIMITED_PRODUCT_FAMILIARITY),
                            List.of(context.chunkId()),
                            List.of(new FindingPayload.EvidenceSpanPayload(
                                    context.chunkId(), context.chunkText())),
                            List.of(),
                            "중복 없는 persona 범위에서 설명하세요.")));
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
    @DisplayName("Provider가 검색 결과에 없는 chunkId를 인용하면 결과를 저장하지 않는다")
    void unretrievedContextChunkReferenceIsRejected() throws Exception {
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        ReflectionTestUtils.setField(fake, "behavior",
                (Function<AnalysisRequest, AnalysisResult>) request ->
                        resultCitingChunks(
                                List.of(Long.MAX_VALUE),
                                List.of(new FindingPayload.EvidenceSpanPayload(
                                        Long.MAX_VALUE, "없는 근거"))));

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
                    return resultCitingChunks(
                            List.of(chunkId, chunkId),
                            List.of(new FindingPayload.EvidenceSpanPayload(
                                    chunkId, request.retrievedContexts().getFirst().chunkText())));
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
    @DisplayName("같은 exact evidence span을 중복 반환하면 anchor를 일부도 저장하지 않는다")
    void duplicateExactEvidenceSpanIsRejected() throws Exception {
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        ReflectionTestUtils.setField(fake, "behavior",
                (Function<AnalysisRequest, AnalysisResult>) request -> {
                    AnalysisRequest.RetrievedContextPayload context =
                            request.retrievedContexts().getFirst();
                    FindingPayload.EvidenceSpanPayload span =
                            new FindingPayload.EvidenceSpanPayload(context.chunkId(), context.chunkText());
                    return resultCitingChunks(List.of(context.chunkId()), List.of(span, span));
                });

        Long analysisId = createAnalysis();

        mockMvc.perform(asPm(get("/api/analyses/{id}", analysisId)))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("PROVIDER_RESPONSE_INVALID"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM findings WHERE analysis_id = ?", Long.class, analysisId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM finding_evidence_anchors anchor
                JOIN findings finding ON finding.id = anchor.finding_id
                WHERE finding.analysis_id = ?
                """, Long.class, analysisId)).isZero();
    }

    @Test
    @DisplayName("pinned 근거 원문에서 UTF-8 범위가 둘 이상인 excerpt는 거부한다")
    void ambiguousExactEvidenceSpanIsRejected() throws Exception {
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        ReflectionTestUtils.setField(fake, "behavior",
                (Function<AnalysisRequest, AnalysisResult>) request -> {
                    AnalysisRequest.RetrievedContextPayload context =
                            request.retrievedContexts().getFirst();
                    return resultCitingChunks(
                            List.of(context.chunkId()),
                            List.of(new FindingPayload.EvidenceSpanPayload(context.chunkId(), "합성")));
                });

        Long analysisId = createAnalysis();

        mockMvc.perform(asPm(get("/api/analyses/{id}", analysisId)))
                .andExpect(jsonPath("$.status").value("FAILED"))
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
                        .content(request(
                                documentId,
                                List.of(1),
                                List.of(LIMITED_PRODUCT_FAMILIARITY_PERSONA_ID))))), traceId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DOCUMENT_NOT_CONFIRMED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("활성 상황 Persona 5개 선택은 분석 요청에 포함되어 202로 수락된다")
    void acceptsFiveActiveSituationPersonas() throws Exception {
        mockMvc.perform(withIdempotencyKey(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(confirmedDocumentId, List.of(1), FIVE_ACTIVE_PERSONA_IDS)))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CREATED"));

        assertThat(((FakeRiskAnalysisProvider) provider).lastRequest().personaCodes())
                .containsExactlyElementsOf(FIVE_ACTIVE_PERSONA_CODES);
    }

    @Test
    @DisplayName("Persona 를 선택하지 않으면 400 INVALID_SELECTION_COUNT")
    void requiresAtLeastOnePersona() throws Exception {
        String traceId = "analysis-no-persona";
        mockMvc.perform(traced(withIdempotencyKey(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(confirmedDocumentId, List.of(1), List.of())))), traceId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("존재하지 않는 Persona 를 선택하면 400 VALIDATION_ERROR")
    void rejectsUnknownPersona() throws Exception {
        String traceId = "analysis-unknown-persona";
        mockMvc.perform(traced(withIdempotencyKey(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(confirmedDocumentId, List.of(1), List.of(999))))), traceId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("비활성 legacy Persona 를 선택하면 400 VALIDATION_ERROR")
    void rejectsInactiveLegacyPersona() throws Exception {
        String traceId = "analysis-inactive-persona";
        mockMvc.perform(traced(withIdempotencyKey(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(confirmedDocumentId, List.of(1), List.of(1))))), traceId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        assertNoAudit(traceId);
    }

    @Test
    @DisplayName("존재하지 않는 근거 문서를 선택하면 400 INVALID_EVIDENCE_DOCUMENT")
    void invalidEvidenceDocument() throws Exception {
        String traceId = "analysis-invalid-evidence";
        mockMvc.perform(traced(withIdempotencyKey(asPm(post("/api/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(
                                confirmedDocumentId,
                                List.of(999),
                                List.of(LIMITED_PRODUCT_FAMILIARITY_PERSONA_ID))))), traceId))
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
                        .content(request(confirmedDocumentId, List.of(1, 2), DEFAULT_PERSONA_IDS)))), traceId))
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
        assertThat(jdbc.queryForMap("""
                SELECT execution.status, execution.error_code, execution.retryable,
                       analysis.current_successful_execution_id
                FROM analysis_executions execution
                JOIN analyses analysis ON analysis.id = execution.analysis_id
                WHERE analysis.id = ?
                """, analysisId))
                .containsEntry("status", "FAILED")
                .containsEntry("error_code", "AI_SERVICE_TEMPORARY_FAILURE")
                .containsEntry("retryable", true)
                .containsEntry("current_successful_execution_id", null);

        useAnchoredDefaultResult();
        mockMvc.perform(traced(asPm(post("/api/analyses/{id}/retry", analysisId)), traceId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.analysisId").value(analysisId));

        mockMvc.perform(asPm(get("/api/analyses/{id}", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.riskScore").isEmpty())
                .andExpect(jsonPath("$.retryable").value(false));
        List<Map<String, Object>> executions = jdbc.queryForList("""
                SELECT id, attempt_no, status, provider_risk_score
                FROM analysis_executions
                WHERE analysis_id = ?
                ORDER BY attempt_no
                """, analysisId);
        assertThat(executions)
                .extracting(
                        row -> row.get("attempt_no"),
                        row -> row.get("status"),
                        row -> row.get("provider_risk_score"))
                .containsExactly(
                        tuple(1, "FAILED", null),
                        tuple(2, "SUCCEEDED", 82));
        assertThat(jdbc.queryForObject("""
                SELECT current_successful_execution_id
                FROM analyses
                WHERE id = ?
                """, Long.class, analysisId))
                .isEqualTo(((Number) executions.get(1).get("id")).longValue());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM analysis_rag_runs rag_run
                JOIN analysis_executions execution
                  ON execution.id = rag_run.analysis_execution_id
                WHERE execution.analysis_id = ?
                """, Long.class, analysisId)).isEqualTo(2L);
        assertAudit(initialTraceId, "ANALYSIS_CREATED", analysisId, 1L, analysisId);
        assertSingleTerminalAudit(
                initialTraceId, "ANALYSIS_FAILED", analysisId, null, analysisId);
        assertAudit(traceId, "ANALYSIS_RETRIED", analysisId, 1L, analysisId);
        assertSingleTerminalAudit(traceId, "ANALYSIS_COMPLETED", analysisId, null, analysisId);
    }

    @Test
    @DisplayName("실행 이력이 없는 고아 token 뒤의 오래된 분석도 복구하고 terminal audit은 한 번만 남긴다")
    void staleRecoveryContinuesAfterMissingExecution() throws Exception {
        Long orphanAnalysisId = createAnalysis();
        Long followingAnalysisId = createAnalysis(
                confirmedDocumentId, List.of(1, 2), FIVE_ACTIVE_PERSONA_IDS, null);
        String orphanToken = "00000000-0000-0000-0000-000000000001";
        String followingToken = "00000000-0000-0000-0000-000000000002";
        OffsetDateTime now = OffsetDateTime.now();

        jdbc.update(
                "UPDATE analyses SET current_successful_execution_id = NULL WHERE id IN (?, ?)",
                orphanAnalysisId,
                followingAnalysisId);
        jdbc.update("""
                INSERT INTO analysis_executions (
                    analysis_id, attempt_no, execution_token, status, retryable,
                    retrieval_version, started_at, created_at
                ) VALUES (?, 2, ?, 'RUNNING', FALSE, 'pgvector-cosine-v1', ?, ?)
                """, followingAnalysisId, followingToken, now.minusMinutes(11), now.minusMinutes(11));
        jdbc.update("""
                UPDATE analyses
                SET status = 'RUNNING', progress = 50, execution_token = ?,
                    error_code = NULL, retryable = FALSE, completed_at = NULL, updated_at = ?
                WHERE id = ?
                """, orphanToken, now.minusMinutes(12), orphanAnalysisId);
        jdbc.update("""
                UPDATE analyses
                SET status = 'RUNNING', progress = 50, execution_token = ?,
                    error_code = NULL, retryable = FALSE, completed_at = NULL, updated_at = ?
                WHERE id = ?
                """, followingToken, now.minusMinutes(11), followingAnalysisId);

        analysisJobService.recoverStaleAnalyses();
        analysisJobService.recoverStaleAnalyses();

        assertThat(jdbc.queryForList("""
                SELECT id, status, error_code, retryable, execution_token
                FROM analyses
                WHERE id IN (?, ?)
                ORDER BY id
                """, orphanAnalysisId, followingAnalysisId))
                .extracting(
                        row -> row.get("status"),
                        row -> row.get("error_code"),
                        row -> row.get("retryable"),
                        row -> row.get("execution_token"))
                .containsExactly(
                        tuple("FAILED", "AI_SERVICE_TEMPORARY_FAILURE", true, orphanToken),
                        tuple("FAILED", "AI_SERVICE_TEMPORARY_FAILURE", true, followingToken));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM analysis_executions
                WHERE analysis_id = ? AND execution_token = ?
                """, Long.class, orphanAnalysisId, orphanToken)).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT status, error_code, retryable
                FROM analysis_executions
                WHERE analysis_id = ? AND execution_token = ?
                """, followingAnalysisId, followingToken))
                .containsEntry("status", "FAILED")
                .containsEntry("error_code", "AI_SERVICE_TEMPORARY_FAILURE")
                .containsEntry("retryable", true);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events
                WHERE action = 'ANALYSIS_FAILED'
                  AND resource_type = 'ANALYSIS'
                  AND resource_id IN (?, ?)
                """, Long.class, orphanAnalysisId, followingAnalysisId)).isEqualTo(2L);
    }

    @Test
    @DisplayName("유실된 오래된 CREATED 요청은 provider나 execution 없이 실패시키고 terminal audit을 한 번만 남긴다")
    void staleCreatedRequestFailsWithoutExecutionOrProvider() {
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        fake.reset();
        String requestToken = "10000000-0000-0000-0000-000000000001";
        Long analysisId = insertCreatedAnalysis(
                requestToken, OffsetDateTime.now().minusMinutes(10));

        analysisJobService.recoverStaleAnalyses();
        analysisJobService.recoverStaleAnalyses();

        assertThat(jdbc.queryForMap("""
                SELECT status, error_code, retryable, execution_token
                FROM analyses
                WHERE id = ?
                """, analysisId))
                .containsEntry("status", "FAILED")
                .containsEntry("error_code", "AI_SERVICE_TEMPORARY_FAILURE")
                .containsEntry("retryable", true)
                .containsEntry("execution_token", requestToken);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM analysis_executions WHERE analysis_id = ?
                """, Long.class, analysisId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM analysis_rag_runs rag_run
                JOIN analysis_executions execution
                  ON execution.id = rag_run.analysis_execution_id
                WHERE execution.analysis_id = ?
                """, Long.class, analysisId)).isZero();
        assertThat(fake.lastRequest()).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events
                WHERE action = 'ANALYSIS_FAILED'
                  AND resource_type = 'ANALYSIS'
                  AND resource_id = ?
                """, Long.class, analysisId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("5분이 지나지 않은 CREATED 요청은 복구 대상이 아니다")
    void freshCreatedRequestIsUntouched() {
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        fake.reset();
        String requestToken = "10000000-0000-0000-0000-000000000002";
        Long analysisId = insertCreatedAnalysis(
                requestToken, OffsetDateTime.now().minusMinutes(4));

        analysisJobService.recoverStaleAnalyses();

        assertThat(jdbc.queryForMap("""
                SELECT status, progress, execution_token
                FROM analyses
                WHERE id = ?
                """, analysisId))
                .containsEntry("status", "CREATED")
                .containsEntry("progress", 0)
                .containsEntry("execution_token", requestToken);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM analysis_executions WHERE analysis_id = ?
                """, Long.class, analysisId)).isZero();
        assertThat(fake.lastRequest()).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events
                WHERE action IN ('ANALYSIS_COMPLETED', 'ANALYSIS_FAILED')
                  AND resource_type = 'ANALYSIS'
                  AND resource_id = ?
                """, Long.class, analysisId)).isZero();
    }

    @Test
    @DisplayName("재시도 B가 수락된 뒤 도착한 이전 이벤트 A는 A의 시나리오로 B를 실행하지 않는다")
    void oldEventCannotRunAcceptedRetryWithOldScenario() {
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        useAnchoredDefaultResult();
        String oldRequestToken = "10000000-0000-0000-0000-000000000003";
        String retryRequestToken = "20000000-0000-0000-0000-000000000003";
        Long analysisId = insertCreatedAnalysis(oldRequestToken, OffsetDateTime.now());
        jdbc.update("""
                UPDATE analyses
                SET status = 'RUNNING', progress = 50, execution_token = ?, updated_at = NOW()
                WHERE id = ?
                """, retryRequestToken, analysisId);

        analysisJobService.handle(new AnalysisRequestedEvent(
                analysisId,
                oldRequestToken,
                "STALE_EVENT_SCENARIO",
                "analysis-old-event-after-retry"));

        assertThat(jdbc.queryForMap("""
                SELECT status, progress, execution_token
                FROM analyses
                WHERE id = ?
                """, analysisId))
                .containsEntry("status", "RUNNING")
                .containsEntry("progress", 50)
                .containsEntry("execution_token", retryRequestToken);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM analysis_executions WHERE analysis_id = ?
                """, Long.class, analysisId)).isZero();
        assertThat(fake.lastRequest()).isNull();
        assertNoAudit("analysis-old-event-after-retry");

        analysisJobService.handle(new AnalysisRequestedEvent(
                analysisId,
                retryRequestToken,
                "RETRY_B_SCENARIO",
                "analysis-current-retry-event"));

        assertThat(fake.lastRequest().scenarioCode()).isEqualTo("RETRY_B_SCENARIO");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM analyses WHERE id = ?
                """, String.class, analysisId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM analysis_executions WHERE analysis_id = ?
                """, Long.class, analysisId)).isEqualTo(1L);
        assertSingleTerminalAudit(
                "analysis-current-retry-event",
                "ANALYSIS_COMPLETED",
                analysisId,
                null,
                analysisId);
    }

    @Test
    @DisplayName("같은 CREATED 이벤트가 중복 배달되어도 실행과 terminal audit은 한 번뿐이다")
    void duplicateCreatedEventsExecuteOnce() {
        FakeRiskAnalysisProvider fake = (FakeRiskAnalysisProvider) provider;
        AtomicInteger providerCalls = new AtomicInteger();
        ReflectionTestUtils.setField(fake, "behavior",
                (Function<AnalysisRequest, AnalysisResult>) request -> {
                    providerCalls.incrementAndGet();
                    AnalysisRequest.RetrievedContextPayload context =
                            request.retrievedContexts().getFirst();
                    return new AnalysisResult(82, "duplicate-event-model", "duplicate-event-prompt",
                            List.of(new FindingPayload(
                                    "중복 이벤트 검증 결과",
                                    Severity.HIGH,
                                    RedTeamRuleCode.STABILITY_KEYWORD,
                                    DEFAULT_PERSONA_CODES,
                                    List.of(context.chunkId()),
                                    List.of(new FindingPayload.EvidenceSpanPayload(
                                            context.chunkId(), context.chunkText())),
                                    List.of(),
                                    "중복 실행하지 않습니다.")));
                });
        String requestToken = "10000000-0000-0000-0000-000000000004";
        String traceId = "analysis-duplicate-created-event";
        Long analysisId = insertCreatedAnalysis(requestToken, OffsetDateTime.now());
        AnalysisRequestedEvent event = new AnalysisRequestedEvent(
                analysisId, requestToken, "DUPLICATE_EVENT_SCENARIO", traceId);

        analysisJobService.handle(event);
        analysisJobService.handle(event);

        assertThat(providerCalls).hasValue(1);
        assertThat(fake.lastRequest().scenarioCode()).isEqualTo("DUPLICATE_EVENT_SCENARIO");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM analysis_executions WHERE analysis_id = ?
                """, Long.class, analysisId)).isEqualTo(1L);
        assertSingleTerminalAudit(traceId, "ANALYSIS_COMPLETED", analysisId, null, analysisId);
    }

    @Test
    @DisplayName("오래된 후보 조회 뒤 token이 바뀌면 잠금 재검증이 stale snapshot을 거부한다")
    void staleCandidateTokenSnapshotIsRejectedUnderLock() {
        String staleRequestToken = "10000000-0000-0000-0000-000000000005";
        String currentRequestToken = "20000000-0000-0000-0000-000000000005";
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(5);
        Long analysisId = insertCreatedAnalysis(
                staleRequestToken, OffsetDateTime.now().minusMinutes(10));
        AnalysisRepository.StaleAnalysisCandidate candidate =
                analysisRepository.findStaleAnalysisCandidates(
                                cutoff, org.springframework.data.domain.PageRequest.of(0, 25))
                        .stream()
                        .filter(value -> value.getId().equals(analysisId))
                        .findFirst()
                        .orElseThrow();
        jdbc.update("""
                UPDATE analyses
                SET execution_token = ?
                WHERE id = ?
                """, currentRequestToken, analysisId);

        Boolean admitted = new TransactionTemplate(transactionManager).execute(status ->
                analysisRepository.findStaleAnalysisWithLock(
                        candidate.getId(), candidate.getExecutionToken(), cutoff).isPresent());

        assertThat(admitted).isFalse();
        assertThat(jdbc.queryForMap("""
                SELECT status, execution_token
                FROM analyses
                WHERE id = ?
                """, analysisId))
                .containsEntry("status", "CREATED")
                .containsEntry("execution_token", currentRequestToken);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_events
                WHERE action IN ('ANALYSIS_COMPLETED', 'ANALYSIS_FAILED')
                  AND resource_type = 'ANALYSIS'
                  AND resource_id = ?
                """, Long.class, analysisId)).isZero();
    }

    @Test
    @DisplayName("재시도 성공은 이전 Finding을 보존하되 결과·검토·승격은 현재 execution에 한정한다")
    void retryRetainsPriorAnchoredFindingHistory() throws Exception {
        Long analysisId = createAnalysis();
        Long firstExecutionId = jdbc.queryForObject("""
                SELECT analysis_execution_id FROM findings WHERE analysis_id = ?
                """, Long.class, analysisId);
        Long firstFindingId = jdbc.queryForObject(
                "SELECT id FROM findings WHERE analysis_id = ?", Long.class, analysisId);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Analysis analysis = analysisRepository.findWithLockById(analysisId).orElseThrow();
            analysis.fail(ErrorCode.AI_SERVICE_TEMPORARY_FAILURE, true);
            analysisRepository.flush();
        });
        useAnchoredResult(37, Severity.LOW, "현재 execution에만 속한 Finding입니다.");
        mockMvc.perform(asPm(post("/api/analyses/{id}/retry", analysisId)))
                .andExpect(status().isAccepted());

        List<Map<String, Object>> findings = jdbc.queryForList("""
                SELECT id, analysis_execution_id, revision_number, supersedes_finding_id
                FROM findings
                WHERE analysis_id = ?
                ORDER BY id
                """, analysisId);
        assertThat(findings).hasSize(2);
        assertThat(findings.getFirst())
                .containsEntry("id", firstFindingId)
                .containsEntry("analysis_execution_id", firstExecutionId)
                .containsEntry("revision_number", 1)
                .containsEntry("supersedes_finding_id", null);
        assertThat(findings.get(1).get("analysis_execution_id")).isNotEqualTo(firstExecutionId);
        assertThat(findings.get(1))
                .containsEntry("revision_number", 1)
                .containsEntry("supersedes_finding_id", null);
        Long currentFindingId = ((Number) findings.get(1).get("id")).longValue();
        Long currentExecutionId = ((Number) findings.get(1).get("analysis_execution_id")).longValue();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM finding_evidence_anchors anchor
                JOIN findings finding ON finding.id = anchor.finding_id
                WHERE finding.analysis_id = ?
                """, Long.class, analysisId)).isEqualTo(2L);

        mockMvc.perform(asPm(get("/api/analyses/{id}/result", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskScore").doesNotExist())
                .andExpect(jsonPath("$.score.state").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.score.value").isEmpty())
                .andExpect(jsonPath("$.currentExecutionId").value(currentExecutionId))
                .andExpect(jsonPath("$.scoreEligible").value(true))
                .andExpect(jsonPath("$.historical").value(false))
                .andExpect(jsonPath("$.findings.length()").value(1))
                .andExpect(jsonPath("$.findings[0].findingId").value(currentFindingId))
                .andExpect(jsonPath("$.findings[0].statement").value("현재 execution에만 속한 Finding입니다."));

        String reviewBody = mockMvc.perform(asPm(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("analysisId", analysisId)))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long reviewId = objectMapper.readTree(reviewBody).get("reviewId").asLong();
        mockMvc.perform(asReviewer(get("/api/reviews/{id}", reviewId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxSeverity").value("LOW"));

        mockMvc.perform(asReviewer(post("/api/reviews/{id}/decision", reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "APPROVED",
                                "selectedFindingIds", List.of(firstFindingId))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.INVALID_FINDING_SELECTION.name()));
        mockMvc.perform(asReviewer(post("/api/reviews/{id}/decision", reviewId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "APPROVED",
                                "selectedFindingIds", List.of(currentFindingId))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskPatternIds.length()").value(1));
    }

    @Test
    @DisplayName("실행 포인터가 없는 레거시 결과는 historical 비점수 결과이며 검토를 생성할 수 없다")
    void legacyResultIsHistoricalAndNotReviewable() throws Exception {
        Long analysisId = createAnalysis();
        jdbc.update("UPDATE analyses SET current_successful_execution_id = NULL WHERE id = ?", analysisId);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM analysis_executions execution
                JOIN findings finding ON finding.analysis_execution_id = execution.id
                WHERE execution.analysis_id = ?
                  AND execution.status = 'SUCCEEDED'
                """, Long.class, analysisId)).isEqualTo(1L);
        mockMvc.perform(asPm(get("/api/analyses/{id}/result", analysisId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskScore").doesNotExist())
                .andExpect(jsonPath("$.score.state").value("NOT_SCORED"))
                .andExpect(jsonPath("$.score.policyVersion").isEmpty())
                .andExpect(jsonPath("$.score.value").isEmpty())
                .andExpect(jsonPath("$.score.notScoredReason").value("LEGACY_RESULT"))
                .andExpect(jsonPath("$.score.ledgerEntries").isEmpty())
                .andExpect(jsonPath("$.currentExecutionId").isEmpty())
                .andExpect(jsonPath("$.scoreEligible").value(false))
                .andExpect(jsonPath("$.historical").value(true))
                .andExpect(jsonPath("$.findings.length()").value(1));

        mockMvc.perform(asPm(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("analysisId", analysisId)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ErrorCode.ANALYSIS_NOT_COMPLETED.name()));
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
            AnalysisRequest.RetrievedContextPayload context =
                    request.retrievedContexts().getFirst();
            return new AnalysisResult(7, "stale-model", "stale-prompt", List.of(new FindingPayload(
                    "이전 execution token의 저장되면 안 되는 Finding",
                    Severity.HIGH,
                    RedTeamRuleCode.STABILITY_KEYWORD,
                    DEFAULT_PERSONA_CODES,
                    List.of(context.chunkId()),
                    List.of(new FindingPayload.EvidenceSpanPayload(
                            context.chunkId(), context.chunkText())),
                    List.of(),
                    "저장되면 안 됩니다.")));
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

            useAnchoredDefaultResult();
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
                .containsEntry("model_version", "mock-risk-v1")
                .containsEntry("prompt_version", "mock-prompt-v1");
        assertThat(analysis.get("risk_score")).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM findings WHERE analysis_id = ?", Long.class, analysisId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM finding_evidence_anchors anchor
                JOIN findings finding ON finding.id = anchor.finding_id
                JOIN analysis_executions execution ON execution.id = finding.analysis_execution_id
                WHERE finding.analysis_id = ?
                  AND execution.status = 'SUCCEEDED'
                """, Long.class, analysisId)).isEqualTo(1L);
        List<Map<String, Object>> executions = jdbc.queryForList("""
                SELECT id, attempt_no, status, provider_risk_score, model_version
                FROM analysis_executions
                WHERE analysis_id = ?
                ORDER BY attempt_no
                """, analysisId);
        assertThat(executions)
                .extracting(
                        row -> row.get("attempt_no"),
                        row -> row.get("status"),
                        row -> row.get("provider_risk_score"),
                        row -> row.get("model_version"))
                .containsExactly(
                        tuple(1, "DISCARDED", null, null),
                        tuple(2, "SUCCEEDED", 82, "mock-risk-v1"));
        assertThat(jdbc.queryForObject(
                "SELECT current_successful_execution_id FROM analyses WHERE id = ?",
                Long.class,
                analysisId))
                .isEqualTo(((Number) executions.get(1).get("id")).longValue());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM analysis_rag_runs rag_run
                JOIN analysis_executions execution
                  ON execution.id = rag_run.analysis_execution_id
                WHERE execution.analysis_id = ?
                """, Long.class, analysisId)).isEqualTo(2L);
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
                        .content(request(
                                othersDocumentId,
                                List.of(1),
                                List.of(LIMITED_PRODUCT_FAMILIARITY_PERSONA_ID))))), traceId))
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
                        .content(request(
                                confirmedDocumentId,
                                List.of(1),
                                List.of(LIMITED_PRODUCT_FAMILIARITY_PERSONA_ID))))), traceId))
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
