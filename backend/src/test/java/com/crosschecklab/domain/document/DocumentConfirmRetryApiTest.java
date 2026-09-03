package com.crosschecklab.domain.document;

import static com.crosschecklab.global.security.DemoAuthenticationFilter.ROLE_HEADER;
import static com.crosschecklab.global.security.DemoAuthenticationFilter.USER_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosschecklab.domain.product.ProductRepository;
import com.crosschecklab.global.common.enums.ExtractStatus;
import com.crosschecklab.global.common.enums.UserRole;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import com.crosschecklab.global.security.DemoUser;
import com.crosschecklab.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// V2 시드: 1 = pm_park(PRODUCT_MANAGER), 2 = reviewer_kim(COMPLIANCE_REVIEWER)
@DisplayName("DOC-003/004 추출 텍스트 확정·재시도 API")
class DocumentConfirmRetryApiTest extends IntegrationTestSupport {

    private static final String PM_ID = "1";
    private static final String REVIEWER_ID = "2";
    private static final String PDF_CONTENT_TYPE = "application/pdf";

    // 1차 추출을 반드시 실패시키는 시나리오. 재시도 경로를 만들 때 쓴다.
    private static final String FAILING_SCENARIO = "EXTRACT_TIMEOUT_THEN_SUCCESS";

    private static final long EXTRACTION_TIMEOUT_MILLIS = 5_000;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductDocumentRepository productDocumentRepository;

    // 동시성 검증은 MockMvc 를 거치지 않고 서비스를 직접 호출한다 (스레드마다 별도 트랜잭션이 필요하다).
    @Autowired
    private ProductDocumentService productDocumentService;

    @BeforeEach
    @AfterEach
    void clearDocumentsAndProducts() {
        productDocumentRepository.deleteAll();
        productRepository.deleteAll();
    }

    private MockHttpServletRequestBuilder asPm(MockHttpServletRequestBuilder builder) {
        return builder.header(USER_ID_HEADER, PM_ID).header(ROLE_HEADER, "PRODUCT_MANAGER");
    }

    private MockHttpServletRequestBuilder asReviewer(MockHttpServletRequestBuilder builder) {
        return builder.header(USER_ID_HEADER, REVIEWER_ID).header(ROLE_HEADER, "COMPLIANCE_REVIEWER");
    }

    private long createProduct() throws Exception {
        String response = mockMvc.perform(asPm(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "확정 테스트 상품", "productType", "SAVINGS")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("productId").asLong();
    }

    private long upload(String scenario) throws Exception {
        long productId = createProduct();
        MockMultipartFile file = new MockMultipartFile("file", "설명서.pdf", PDF_CONTENT_TYPE,
                "본문".getBytes(StandardCharsets.UTF_8));

        var request = multipart("/api/products/{productId}/documents", productId).file(file);
        if (scenario != null) {
            request.header(ProductDocumentController.SCENARIO_HEADER, scenario);
        }

        String response = mockMvc.perform(asPm(request))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("documentId").asLong();
    }

    private ExtractStatus awaitExtractionFinished(long documentId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + EXTRACTION_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            ExtractStatus status = productDocumentRepository.findById(documentId)
                    .map(ProductDocument::getExtractStatus)
                    .orElseThrow(() -> new IllegalStateException("문서가 사라졌습니다: " + documentId));
            if (status == ExtractStatus.READY || status == ExtractStatus.FAILED) {
                return status;
            }
            Thread.sleep(50);
        }
        return fail("문서 %d 의 추출이 %dms 안에 끝나지 않았습니다.".formatted(documentId, EXTRACTION_TIMEOUT_MILLIS));
    }

    // 추출이 끝난 READY 문서
    private long readyDocument() throws Exception {
        long documentId = upload(null);
        assertThat(awaitExtractionFinished(documentId)).isEqualTo(ExtractStatus.READY);
        return documentId;
    }

    // 1차 추출에 실패한 FAILED 문서
    private long failedDocument() throws Exception {
        long documentId = upload(FAILING_SCENARIO);
        assertThat(awaitExtractionFinished(documentId)).isEqualTo(ExtractStatus.FAILED);
        return documentId;
    }

    private MockHttpServletRequestBuilder patchText(long documentId, String text, Boolean confirmed)
            throws Exception {
        // null 값을 담아야 해서 Map.of 대신 HashMap 을 쓴다.
        Map<String, Object> body = new HashMap<>();
        body.put("extractedText", text);
        body.put("confirmed", confirmed);
        return patch("/api/documents/{documentId}/text", documentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    private JsonNode fetchDocument(long documentId) throws Exception {
        String response = mockMvc.perform(asPm(get("/api/documents/{documentId}", documentId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    @Nested
    @DisplayName("PATCH /api/documents/{documentId}/text")
    class UpdateText {

        @Test
        @DisplayName("담당자가 텍스트를 고치고 확정하면 200 과 함께 확인자·확인 시각이 기록된다")
        void updatesAndConfirms() throws Exception {
            long documentId = readyDocument();

            mockMvc.perform(asPm(patchText(documentId, "담당자가 손본 최종 텍스트", true)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.documentId").value((int) documentId))
                    .andExpect(jsonPath("$.extractedText").value("담당자가 손본 최종 텍스트"))
                    .andExpect(jsonPath("$.confirmed").value(true))
                    .andExpect(jsonPath("$.confirmedBy").value(1))
                    .andExpect(jsonPath("$.confirmedAt").isNotEmpty())
                    // 확정은 텍스트 상태를 바꾸지 않는다. 여전히 READY 다.
                    .andExpect(jsonPath("$.extractStatus").value("READY"));
        }

        @Test
        @DisplayName("수정 결과가 이후 조회에도 그대로 남는다")
        void persistsUpdate() throws Exception {
            long documentId = readyDocument();
            mockMvc.perform(asPm(patchText(documentId, "저장된 텍스트", true)))
                    .andExpect(status().isOk());

            JsonNode document = fetchDocument(documentId);
            assertThat(document.get("extractedText").asText()).isEqualTo("저장된 텍스트");
            assertThat(document.get("confirmed").asBoolean()).isTrue();
            assertThat(document.get("confirmedBy").asLong()).isEqualTo(1L);
        }

        @Test
        @DisplayName("confirmed=false 로 저장하면 텍스트만 바뀌고 확인 정보는 비어 있다")
        void savesWithoutConfirming() throws Exception {
            long documentId = readyDocument();

            mockMvc.perform(asPm(patchText(documentId, "아직 검토 중인 텍스트", false)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.extractedText").value("아직 검토 중인 텍스트"))
                    .andExpect(jsonPath("$.confirmed").value(false))
                    .andExpect(jsonPath("$.confirmedBy").value(nullValue()))
                    .andExpect(jsonPath("$.confirmedAt").value(nullValue()));
        }

        @Test
        @DisplayName("확정한 뒤 confirmed=false 로 되돌리면 확인자·확인 시각도 지워진다")
        void clearsConfirmationWhenUnconfirmed() throws Exception {
            long documentId = readyDocument();
            mockMvc.perform(asPm(patchText(documentId, "확정본", true)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.confirmedBy").value(1));

            mockMvc.perform(asPm(patchText(documentId, "다시 검토가 필요한 텍스트", false)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.confirmed").value(false))
                    .andExpect(jsonPath("$.confirmedBy").value(nullValue()))
                    .andExpect(jsonPath("$.confirmedAt").value(nullValue()));
        }

        @Test
        @DisplayName("READY 가 아니면 409 DOCUMENT_NOT_READY")
        void rejectsNonReadyDocument() throws Exception {
            long documentId = failedDocument();

            mockMvc.perform(asPm(patchText(documentId, "실패한 문서를 고쳐본다", true)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("DOCUMENT_NOT_READY"));
        }

        @Test
        @DisplayName("extractedText 가 비어 있으면 400")
        void rejectsBlankText() throws Exception {
            long documentId = readyDocument();

            mockMvc.perform(asPm(patchText(documentId, "   ", true)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("extractedText"));
        }

        @Test
        @DisplayName("confirmed 가 없으면 400")
        void rejectsMissingConfirmed() throws Exception {
            long documentId = readyDocument();

            mockMvc.perform(asPm(patchText(documentId, "텍스트", null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("confirmed"));
        }

        @Test
        @DisplayName("검토자는 수정할 수 없다 — 403")
        void rejectsReviewer() throws Exception {
            long documentId = readyDocument();

            mockMvc.perform(asReviewer(patchText(documentId, "검토자가 고쳐본다", true)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_OWNERSHIP"));
        }

        @Test
        @DisplayName("없는 문서면 404")
        void rejectsUnknownDocument() throws Exception {
            mockMvc.perform(asPm(patchText(999_999L, "텍스트", true)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("DOCUMENT_NOT_FOUND"));
        }

        @Test
        @DisplayName("데모 헤더가 없으면 401")
        void requiresAuthentication() throws Exception {
            long documentId = readyDocument();

            mockMvc.perform(patchText(documentId, "텍스트", true))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("POST /api/documents/{documentId}/retry")
    class Retry {

        @Test
        @DisplayName("실패한 문서를 재시도하면 202 와 statusUrl 을 반환하고 EXTRACTING 으로 전이한다")
        void acceptsRetryOfFailedDocument() throws Exception {
            long documentId = failedDocument();

            mockMvc.perform(asPm(post("/api/documents/{documentId}/retry", documentId)))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.documentId").value((int) documentId))
                    .andExpect(jsonPath("$.extractStatus").value("EXTRACTING"))
                    .andExpect(jsonPath("$.statusUrl").value("/api/documents/" + documentId));
        }

        @Test
        @DisplayName("재시도하면 2차 추출이 성공해 READY 가 되고 텍스트가 채워진다")
        void succeedsOnSecondAttempt() throws Exception {
            long documentId = failedDocument();

            mockMvc.perform(asPm(post("/api/documents/{documentId}/retry", documentId)))
                    .andExpect(status().isAccepted());

            assertThat(awaitExtractionFinished(documentId)).isEqualTo(ExtractStatus.READY);

            JsonNode document = fetchDocument(documentId);
            assertThat(document.get("extractedText").asText()).isNotBlank();
            // 새 텍스트가 들어왔으므로 확인 상태는 여전히 미확인이다.
            assertThat(document.get("confirmed").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("READY 문서는 재시도할 수 없다 — 409 DOCUMENT_NOT_RETRYABLE")
        void rejectsReadyDocument() throws Exception {
            long documentId = readyDocument();

            mockMvc.perform(asPm(post("/api/documents/{documentId}/retry", documentId)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("DOCUMENT_NOT_RETRYABLE"));
        }

        @Test
        @DisplayName("재시도 중인 문서를 또 재시도하면 409")
        void rejectsDoubleRetry() throws Exception {
            long documentId = failedDocument();
            mockMvc.perform(asPm(post("/api/documents/{documentId}/retry", documentId)))
                    .andExpect(status().isAccepted());

            // 2차 추출이 끝나면 READY 가 되므로, 어느 쪽이든 FAILED 가 아니라서 409 여야 한다.
            mockMvc.perform(asPm(post("/api/documents/{documentId}/retry", documentId)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("DOCUMENT_NOT_RETRYABLE"));
        }

        @Test
        @DisplayName("동시에 재시도해도 한 건만 성공하고 나머지는 409 다")
        void allowsOnlyOneConcurrentRetry() throws Exception {
            long documentId = failedDocument();
            // requireOwner 가 id 만 보므로 시드 담당자와 같은 id 면 충분하다.
            DemoUser pm = new DemoUser(1L, "pm_park", "박서준 대리", UserRole.PRODUCT_MANAGER);

            int concurrency = 4;
            CountDownLatch ready = new CountDownLatch(concurrency);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(concurrency);

            try {
                // 성공이면 null, 거절이면 그 ErrorCode 를 돌려준다.
                List<Future<ErrorCode>> submitted = new ArrayList<>();
                for (int i = 0; i < concurrency; i++) {
                    submitted.add(pool.submit(() -> {
                        ready.countDown();
                        start.await();
                        try {
                            productDocumentService.retryExtraction(documentId, pm);
                            return null;
                        } catch (BusinessException e) {
                            return e.getErrorCode();
                        }
                    }));
                }

                // 모든 스레드가 대기선에 선 뒤에 한꺼번에 출발시킨다.
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                List<ErrorCode> outcomes = new ArrayList<>();
                for (Future<ErrorCode> result : submitted) {
                    outcomes.add(result.get(10, TimeUnit.SECONDS));
                }

                assertThat(outcomes.stream().filter(Objects::isNull).count()).isEqualTo(1);
                assertThat(outcomes.stream().filter(Objects::nonNull).toList())
                        .hasSize(concurrency - 1)
                        .containsOnly(ErrorCode.DOCUMENT_NOT_RETRYABLE);
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("검토자는 재시도할 수 없다 — 403")
        void rejectsReviewer() throws Exception {
            long documentId = failedDocument();

            mockMvc.perform(asReviewer(post("/api/documents/{documentId}/retry", documentId)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_OWNERSHIP"));
        }

        @Test
        @DisplayName("없는 문서면 404")
        void rejectsUnknownDocument() throws Exception {
            mockMvc.perform(asPm(post("/api/documents/{documentId}/retry", 999_999L)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("DOCUMENT_NOT_FOUND"));
        }

        @Test
        @DisplayName("데모 헤더가 없으면 401")
        void requiresAuthentication() throws Exception {
            long documentId = failedDocument();

            mockMvc.perform(post("/api/documents/{documentId}/retry", documentId))
                    .andExpect(status().isUnauthorized());
        }
    }
}
