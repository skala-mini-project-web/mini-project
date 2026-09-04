package com.crosschecklab.domain.groundtruth;

import static com.crosschecklab.global.security.DemoAuthenticationFilter.ROLE_HEADER;
import static com.crosschecklab.global.security.DemoAuthenticationFilter.USER_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosschecklab.domain.document.ProductDocument;
import com.crosschecklab.domain.document.ProductDocumentRepository;
import com.crosschecklab.domain.document.ProductDocumentService;
import com.crosschecklab.domain.document.dto.DocumentTextUpdateRequest;
import com.crosschecklab.domain.product.ProductRepository;
import com.crosschecklab.global.common.enums.UserRole;
import com.crosschecklab.global.security.DemoUser;
import com.crosschecklab.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("확정 문서 기반 공식 사실 API")
class GroundTruthFactApiTest extends IntegrationTestSupport {

    private static final String PM_ID = "1";
    private static final String OTHER_PM_ID = "99";
    private static final String REVIEWER_ID = "2";
    private static final long EXTRACTION_TIMEOUT_MILLIS = 5_000;

    @Autowired
    private GroundTruthFactRepository groundTruthFactRepository;

    @Autowired
    private ProductDocumentRepository productDocumentRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductDocumentService productDocumentService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        clearDomainData();
        jdbcTemplate.update("""
                insert into users (id, username, name, role, active, created_at, updated_at)
                values (99, 'pm_other', '다른 담당자', 'PRODUCT_MANAGER', true, now(), now())
                """);
    }

    @AfterEach
    void tearDown() {
        clearDomainData();
        jdbcTemplate.update("delete from users where id = 99");
    }

    private void clearDomainData() {
        groundTruthFactRepository.deleteAll();
        productDocumentRepository.deleteAll();
        productRepository.deleteAll();
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String id, String role) {
        return request.header(USER_ID_HEADER, id).header(ROLE_HEADER, role);
    }

    private MockHttpServletRequestBuilder asPm(MockHttpServletRequestBuilder request) {
        return as(request, PM_ID, "PRODUCT_MANAGER");
    }

    private long readyDocument() throws Exception {
        String productResponse = mockMvc.perform(asPm(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "공식 사실 상품", "productType", "SAVINGS")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long productId = objectMapper.readTree(productResponse).get("productId").asLong();

        MockMultipartFile file = new MockMultipartFile("file", "설명서.pdf", "application/pdf",
                "실제 업로드 본문".getBytes(StandardCharsets.UTF_8));
        String uploadResponse = mockMvc.perform(asPm(
                        multipart("/api/products/{productId}/documents", productId).file(file)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        long documentId = objectMapper.readTree(uploadResponse).get("documentId").asLong();

        long deadline = System.currentTimeMillis() + EXTRACTION_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            ProductDocument document = productDocumentRepository.findById(documentId).orElseThrow();
            if (document.isReady()) {
                return documentId;
            }
            if (document.isFailed()) {
                return fail("문서 추출이 실패했습니다.");
            }
            Thread.sleep(50);
        }
        return fail("문서 추출이 제한 시간 안에 끝나지 않았습니다.");
    }

    private void confirm(long documentId, String text) throws Exception {
        mockMvc.perform(asPm(patch("/api/documents/{documentId}/text", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("extractedText", text, "confirmed", true)))))
                .andExpect(status().isOk());
    }

    private JsonNode list(long documentId) throws Exception {
        String response = mockMvc.perform(asPm(get(
                        "/api/product-documents/{documentId}/ground-truth-facts", documentId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("items").get(0);
    }

    private MockHttpServletRequestBuilder decision(long factId, String status, String value) throws Exception {
        return put("/api/ground-truth-facts/{factId}/verification", factId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("verificationStatus", status, "value", value)));
    }

    @Test
    @DisplayName("텍스트 확정은 AI나 fixture가 아닌 확정문 전체로 단일 CANDIDATE snapshot을 만든다")
    void createsAndListsSnapshotOnConfirmation() throws Exception {
        long documentId = readyDocument();
        String confirmedText = "고객이 직접 확인한 최종 상품 설명 전체";

        confirm(documentId, confirmedText);

        mockMvc.perform(asPm(get("/api/product-documents/{documentId}/ground-truth-facts", documentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].factId").isNumber())
                .andExpect(jsonPath("$.items[0].label").value("확정 문서 텍스트"))
                .andExpect(jsonPath("$.items[0].value").value(confirmedText))
                .andExpect(jsonPath("$.items[0].importance").value("HIGH"))
                .andExpect(jsonPath("$.items[0].verificationStatus").value("CANDIDATE"))
                .andExpect(jsonPath("$.items[0].extractionSource").value("CONFIRMED_DOCUMENT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"VERIFIED", "REJECTED"})
    @DisplayName("담당자는 현재 snapshot 값 그대로 VERIFIED 또는 REJECTED 결정할 수 있다")
    void acceptsFinalDecision(String verificationStatus) throws Exception {
        long documentId = readyDocument();
        confirm(documentId, "결정할 사실 값");
        JsonNode fact = list(documentId);

        mockMvc.perform(asPm(decision(fact.get("factId").asLong(), verificationStatus,
                        fact.get("value").asText())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value(verificationStatus))
                .andExpect(jsonPath("$.value").value("결정할 사실 값"))
                .andExpect(jsonPath("$.verifiedBy").value(1))
                .andExpect(jsonPath("$.verifiedAt").isNotEmpty());
    }

    @Test
    @DisplayName("결정자는 문서 확인자가 아니라 현재 인증된 담당자로 기록한다")
    void recordsAuthenticatedVerifierInsteadOfDocumentConfirmer() throws Exception {
        long documentId = readyDocument();
        confirm(documentId, "서로 다른 확인자와 결정자");
        JsonNode fact = list(documentId);
        jdbcTemplate.update("update product_documents set confirmed_by = 99 where id = ?", documentId);

        mockMvc.perform(asPm(decision(fact.get("factId").asLong(), "VERIFIED",
                        fact.get("value").asText())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verifiedBy").value(1));

        assertThat(groundTruthFactRepository.findById(fact.get("factId").asLong())
                .orElseThrow().getVerifiedBy()).isEqualTo(1L);
    }

    @Test
    @DisplayName("확정 텍스트가 바뀌면 같은 단일 snapshot이 새 값의 CANDIDATE로 초기화된다")
    void invalidatesDecisionWhenConfirmedTextChanges() throws Exception {
        long documentId = readyDocument();
        confirm(documentId, "첫 확정본");
        JsonNode original = list(documentId);
        long factId = original.get("factId").asLong();
        mockMvc.perform(asPm(decision(factId, "VERIFIED", "첫 확정본")))
                .andExpect(status().isOk());

        confirm(documentId, "수정 후 재확정본");

        mockMvc.perform(asPm(get("/api/product-documents/{documentId}/ground-truth-facts", documentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].factId").value((int) factId))
                .andExpect(jsonPath("$.items[0].value").value("수정 후 재확정본"))
                .andExpect(jsonPath("$.items[0].verificationStatus").value("CANDIDATE"));
    }

    @Test
    @DisplayName("재확정이 문서 잠금을 보유한 동안의 이전 snapshot 결정은 새 CANDIDATE를 덮어쓰지 않는다")
    void confirmationWinsOverStaleConcurrentVerification() throws Exception {
        long documentId = readyDocument();
        confirm(documentId, "동시성 이전 확정본");
        long factId = list(documentId).get("factId").asLong();
        DemoUser owner = new DemoUser(1L, "pm", "상품 담당자", UserRole.PRODUCT_MANAGER);
        CountDownLatch confirmationRefreshed = new CountDownLatch(1);
        CountDownLatch allowConfirmationCommit = new CountDownLatch(1);
        AtomicInteger confirmationBackendPid = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Void> confirmation = executor.submit(() -> {
                transactionTemplate.executeWithoutResult(status -> {
                    confirmationBackendPid.set(
                            jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
                    productDocumentService.updateText(documentId,
                            new DocumentTextUpdateRequest("동시성 이후 확정본", true), owner);
                    confirmationRefreshed.countDown();
                    await(allowConfirmationCommit);
                });
                return null;
            });
            assertThat(confirmationRefreshed.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Void> staleVerification = executor.submit(() -> {
                mockMvc.perform(asPm(decision(factId, "VERIFIED", "동시성 이전 확정본")))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                        .andExpect(jsonPath("$.fieldErrors[0].field").value("value"));
                return null;
            });
            awaitDocumentLockWait(confirmationBackendPid.get(), staleVerification);

            allowConfirmationCommit.countDown();
            confirmation.get(5, TimeUnit.SECONDS);
            staleVerification.get(5, TimeUnit.SECONDS);
        } finally {
            allowConfirmationCommit.countDown();
            executor.shutdownNow();
        }

        ProductDocument document = productDocumentRepository.findById(documentId).orElseThrow();
        GroundTruthFact fact = groundTruthFactRepository.findById(factId).orElseThrow();
        assertThat(document.getExtractedText()).isEqualTo("동시성 이후 확정본");
        assertThat(fact.getValue()).isEqualTo("동시성 이후 확정본");
        assertThat(fact.getVerificationStatus()).isEqualTo(GroundTruthFact.VerificationStatus.CANDIDATE);
        assertThat(fact.getVerifiedBy()).isNull();
    }

    private void awaitDocumentLockWait(int blockingPid, Future<?> verification) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            boolean waitingForDocumentLock = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                    select exists (
                        select 1
                        from pg_stat_activity activity
                        where activity.datname = current_database()
                          and activity.wait_event_type = 'Lock'
                          and ? = any(pg_blocking_pids(activity.pid))
                          and activity.query ilike '%product_documents%'
                    )
                    """, Boolean.class, blockingPid));
            if (waitingForDocumentLock) {
                return;
            }
            if (verification.isDone()) {
                verification.get();
                fail("검증 요청이 PostgreSQL 문서 잠금에서 대기하지 않았습니다.");
            }
        }
        fail("검증 요청의 PostgreSQL 문서 잠금 대기가 제한 시간 안에 관찰되지 않았습니다.");
    }

    @Test
    @DisplayName("요청 value 변조는 거부하고 서버 snapshot 상태를 바꾸지 않는다")
    void rejectsTamperedValue() throws Exception {
        long documentId = readyDocument();
        confirm(documentId, "서버가 보관한 사실");
        long factId = list(documentId).get("factId").asLong();

        mockMvc.perform(asPm(decision(factId, "VERIFIED", "클라이언트가 바꾼 사실")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("value"));
        assertThat(groundTruthFactRepository.findById(factId).orElseThrow().getVerificationStatus().name())
                .isEqualTo("CANDIDATE");
    }

    @Test
    @DisplayName("CANDIDATE는 결정 상태로 받을 수 없다")
    void rejectsCandidateDecision() throws Exception {
        long documentId = readyDocument();
        confirm(documentId, "후보 사실");
        JsonNode fact = list(documentId);

        mockMvc.perform(asPm(decision(fact.get("factId").asLong(), "CANDIDATE", "후보 사실")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("verificationStatus"));
    }

    @Test
    @DisplayName("다른 상품 담당자는 목록과 결정을 조회·변경할 수 없다")
    void enforcesProductOwnership() throws Exception {
        long documentId = readyDocument();
        confirm(documentId, "소유자 전용 사실");
        JsonNode fact = list(documentId);

        mockMvc.perform(as(get("/api/product-documents/{documentId}/ground-truth-facts", documentId),
                        OTHER_PM_ID, "PRODUCT_MANAGER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_OWNERSHIP"));
        mockMvc.perform(as(decision(fact.get("factId").asLong(), "VERIFIED", "소유자 전용 사실"),
                        OTHER_PM_ID, "PRODUCT_MANAGER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_OWNERSHIP"));
    }

    @Test
    @DisplayName("검토자는 공식 사실 목록과 결정 경로를 사용할 수 없다")
    void enforcesProductManagerRole() throws Exception {
        long documentId = readyDocument();
        confirm(documentId, "PM 역할 전용 사실");
        JsonNode fact = list(documentId);

        mockMvc.perform(as(get("/api/product-documents/{documentId}/ground-truth-facts", documentId),
                        REVIEWER_ID, "COMPLIANCE_REVIEWER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mockMvc.perform(as(decision(fact.get("factId").asLong(), "REJECTED", "PM 역할 전용 사실"),
                        REVIEWER_ID, "COMPLIANCE_REVIEWER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("확정되지 않은 READY 문서는 사실 목록을 제공하지 않는다")
    void requiresConfirmedDocument() throws Exception {
        long documentId = readyDocument();

        mockMvc.perform(asPm(get("/api/product-documents/{documentId}/ground-truth-facts", documentId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DOCUMENT_NOT_CONFIRMED"));
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시성 테스트 latch 대기 시간이 초과되었습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트가 중단되었습니다.", exception);
        }
    }
}
