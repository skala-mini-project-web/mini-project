package com.crosschecklab.domain.document;

import static com.crosschecklab.global.security.DemoAuthenticationFilter.ROLE_HEADER;
import static com.crosschecklab.global.security.DemoAuthenticationFilter.USER_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosschecklab.domain.product.ProductRepository;
import com.crosschecklab.global.common.enums.ExtractStatus;
import com.crosschecklab.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

// V2 시드: 1 = pm_park(PRODUCT_MANAGER), 2 = reviewer_kim(COMPLIANCE_REVIEWER)
@DisplayName("DOC-001/002 문서 업로드·추출 API")
class DocumentApiTest extends IntegrationTestSupport {

    private static final String PM_ID = "1";
    private static final String REVIEWER_ID = "2";
    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final String PPTX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    // 비동기 추출이 끝나기를 기다리는 상한. Mock 추출은 수 ms 안에 끝나므로 넉넉한 값이다.
    private static final long EXTRACTION_TIMEOUT_MILLIS = 5_000;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductDocumentRepository productDocumentRepository;

    // 컨테이너는 JVM 당 하나라 여기서 만든 데이터가 다른 테스트로 새어 나간다.
    // 문서를 먼저 지워야 상품 삭제가 FK 에 걸리지 않는다.
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
                                Map.of("name", "문서 테스트 상품", "productType", "INVESTMENT")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("productId").asLong();
    }

    private MockMultipartFile pdf(String fileName, String content) {
        return new MockMultipartFile("file", fileName, PDF_CONTENT_TYPE,
                content.getBytes(StandardCharsets.UTF_8));
    }

    private MockMultipartHttpServletRequestBuilder uploadTo(long productId) {
        return multipart("/api/products/{productId}/documents", productId);
    }

    // 업로드 후 documentId 를 돌려준다.
    private long upload(long productId, MockMultipartFile file, String scenario) throws Exception {
        MockMultipartHttpServletRequestBuilder request = uploadTo(productId).file(file);
        if (scenario != null) {
            request.header(ProductDocumentController.SCENARIO_HEADER, scenario);
        }
        String response = mockMvc.perform(asPm(request))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("documentId").asLong();
    }

    // 추출은 별도 스레드에서 커밋되므로 READY/FAILED 가 될 때까지 폴링한다.
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

    private JsonNode fetchDocument(long documentId) throws Exception {
        String response = mockMvc.perform(asPm(get("/api/documents/{documentId}", documentId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    @Nested
    @DisplayName("POST /api/products/{productId}/documents")
    class Upload {

        @Test
        @DisplayName("담당자가 PDF 를 올리면 202 와 statusUrl 을 반환하고 상태는 UPLOADED 로 시작한다")
        void acceptsPdfUpload() throws Exception {
            long productId = createProduct();

            mockMvc.perform(asPm(uploadTo(productId).file(pdf("설명서.pdf", "본문"))))
                    .andExpect(status().isAccepted())
                    .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern("/api/documents/\\d+")))
                    .andExpect(jsonPath("$.documentId").isNumber())
                    .andExpect(jsonPath("$.productId").value((int) productId))
                    .andExpect(jsonPath("$.fileName").value("설명서.pdf"))
                    // 202 는 "접수했다"는 의미다. 이 시점에 추출이 끝나 있으면 안 된다.
                    .andExpect(jsonPath("$.extractStatus").value("UPLOADED"))
                    .andExpect(jsonPath("$.statusUrl").value(org.hamcrest.Matchers.matchesPattern("/api/documents/\\d+")));
        }

        @Test
        @DisplayName("PPTX 도 허용한다")
        void acceptsPptxUpload() throws Exception {
            long productId = createProduct();
            MockMultipartFile pptx = new MockMultipartFile("file", "설명서.pptx", PPTX_CONTENT_TYPE,
                    "슬라이드".getBytes(StandardCharsets.UTF_8));

            String response = mockMvc.perform(asPm(uploadTo(productId).file(pptx)))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString();

            long documentId = objectMapper.readTree(response).get("documentId").asLong();
            assertThat(fetchDocument(documentId).get("mediaType").asText()).isEqualTo(PPTX_CONTENT_TYPE);
        }

        @Test
        @DisplayName("업로드 후 백그라운드 추출이 끝나면 READY 가 되고 추출 텍스트가 채워진다")
        void extractsTextAsynchronously() throws Exception {
            long productId = createProduct();
            long documentId = upload(productId, pdf("설명서.pdf", "본문"), null);

            assertThat(awaitExtractionFinished(documentId)).isEqualTo(ExtractStatus.READY);

            JsonNode document = fetchDocument(documentId);
            assertThat(document.get("extractStatus").asText()).isEqualTo("READY");
            assertThat(document.get("extractedText").asText()).isNotBlank();
            assertThat(document.get("confirmed").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("PDF/PPTX 가 아니면 400 INVALID_FILE_TYPE")
        void rejectsUnsupportedMediaType() throws Exception {
            long productId = createProduct();
            MockMultipartFile image = new MockMultipartFile("file", "표지.png", "image/png",
                    "not-a-document".getBytes(StandardCharsets.UTF_8));

            mockMvc.perform(asPm(uploadTo(productId).file(image)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_FILE_TYPE"));
        }

        @Test
        @DisplayName("Content-Type 이 octet-stream 이어도 확장자가 pdf 면 허용한다")
        void fallsBackToFileExtension() throws Exception {
            long productId = createProduct();
            MockMultipartFile ambiguous = new MockMultipartFile("file", "설명서.pdf",
                    "application/octet-stream", "본문".getBytes(StandardCharsets.UTF_8));

            mockMvc.perform(asPm(uploadTo(productId).file(ambiguous)))
                    .andExpect(status().isAccepted());
        }

        @Test
        @DisplayName("빈 파일은 400 VALIDATION_ERROR")
        void rejectsEmptyFile() throws Exception {
            long productId = createProduct();
            MockMultipartFile empty = new MockMultipartFile("file", "빈파일.pdf", PDF_CONTENT_TYPE, new byte[0]);

            mockMvc.perform(asPm(uploadTo(productId).file(empty)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("file"));
        }

        @Test
        @DisplayName("10MB 를 넘으면 413 FILE_TOO_LARGE")
        void rejectsTooLargeFile() throws Exception {
            long productId = createProduct();
            byte[] oversized = new byte[(int) ProductDocumentService.MAX_FILE_SIZE_BYTES + 1];
            MockMultipartFile large = new MockMultipartFile("file", "대용량.pdf", PDF_CONTENT_TYPE, oversized);

            mockMvc.perform(asPm(uploadTo(productId).file(large)))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(jsonPath("$.errorCode").value("FILE_TOO_LARGE"));
        }

        @Test
        @DisplayName("같은 내용을 올리면 checksum 이 항상 같다")
        void producesDeterministicChecksum() throws Exception {
            long productId = createProduct();
            long first = upload(productId, pdf("첫번째.pdf", "동일한 본문"), null);
            long second = upload(productId, pdf("두번째.pdf", "동일한 본문"), null);

            String firstChecksum = fetchDocument(first).get("checksum").asText();
            String secondChecksum = fetchDocument(second).get("checksum").asText();

            assertThat(firstChecksum).hasSize(64).isEqualTo(secondChecksum);
        }

        @Test
        @DisplayName("같은 시나리오는 언제나 같은 텍스트를 추출한다")
        void producesDeterministicText() throws Exception {
            long productId = createProduct();
            long first = upload(productId, pdf("a.pdf", "내용 A"), "ACCESSIBILITY_LOW");
            long second = upload(productId, pdf("b.pdf", "내용 B"), "ACCESSIBILITY_LOW");

            awaitExtractionFinished(first);
            awaitExtractionFinished(second);

            assertThat(fetchDocument(first).get("extractedText").asText())
                    .isEqualTo(fetchDocument(second).get("extractedText").asText());
        }

        @Test
        @DisplayName("X-Demo-Scenario 로 시나리오를 지정할 수 있다")
        void honoursScenarioHeader() throws Exception {
            long productId = createProduct();
            long documentId = upload(productId, pdf("아무거나.pdf", "내용"), "ACCESSIBILITY_LOW");

            assertThat(awaitExtractionFinished(documentId)).isEqualTo(ExtractStatus.READY);
            assertThat(fetchDocument(documentId).get("extractedText").asText())
                    .contains("안심케어 파생결합사채");
        }

        @Test
        @DisplayName("모르는 시나리오 코드는 400")
        void rejectsUnknownScenario() throws Exception {
            long productId = createProduct();

            mockMvc.perform(asPm(uploadTo(productId).file(pdf("설명서.pdf", "본문"))
                            .header(ProductDocumentController.SCENARIO_HEADER, "NOT_A_SCENARIO")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("X-Demo-Scenario"));
        }

        @Test
        @DisplayName("실패 시나리오는 1차 시도에서 FAILED 가 되고 텍스트가 비어 있다")
        void marksFailedOnExtractionError() throws Exception {
            long productId = createProduct();
            long documentId = upload(productId, pdf("설명서.pdf", "본문"), "EXTRACT_TIMEOUT_THEN_SUCCESS");

            assertThat(awaitExtractionFinished(documentId)).isEqualTo(ExtractStatus.FAILED);

            JsonNode document = fetchDocument(documentId);
            assertThat(document.get("extractStatus").asText()).isEqualTo("FAILED");
            assertThat(document.get("extractedText").isNull()).isTrue();
        }

        @Test
        @DisplayName("파일명이 시나리오 패턴과 맞으면 헤더 없이도 해당 시나리오로 추출한다")
        void matchesScenarioByFileName() throws Exception {
            long productId = createProduct();
            long documentId = upload(productId, pdf("행복저축 정기적금 설명서.pdf", "본문"), null);

            assertThat(awaitExtractionFinished(documentId)).isEqualTo(ExtractStatus.READY);
            assertThat(fetchDocument(documentId).get("extractedText").asText()).contains("행복저축 정기적금");
        }

        @Test
        @DisplayName("담당하지 않는 상품에는 올릴 수 없다 (검토자 포함) — 403")
        void rejectsNonOwner() throws Exception {
            long productId = createProduct();

            mockMvc.perform(asReviewer(uploadTo(productId).file(pdf("설명서.pdf", "본문"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_OWNERSHIP"));
        }

        @Test
        @DisplayName("없는 상품이면 404")
        void rejectsUnknownProduct() throws Exception {
            mockMvc.perform(asPm(uploadTo(999_999L).file(pdf("설명서.pdf", "본문"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));
        }

        @Test
        @DisplayName("데모 헤더가 없으면 401")
        void requiresAuthentication() throws Exception {
            long productId = createProduct();

            mockMvc.perform(uploadTo(productId).file(pdf("설명서.pdf", "본문")))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api/documents/{documentId}")
    class FindById {

        @Test
        @DisplayName("소유자는 문서 메타와 추출 결과를 조회할 수 있다")
        void allowsOwner() throws Exception {
            long productId = createProduct();
            long documentId = upload(productId, pdf("설명서.pdf", "본문"), null);
            awaitExtractionFinished(documentId);

            mockMvc.perform(asPm(get("/api/documents/{documentId}", documentId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.documentId").value((int) documentId))
                    .andExpect(jsonPath("$.productId").value((int) productId))
                    .andExpect(jsonPath("$.fileName").value("설명서.pdf"))
                    .andExpect(jsonPath("$.mediaType").value(PDF_CONTENT_TYPE))
                    .andExpect(jsonPath("$.fileSize").isNumber())
                    .andExpect(jsonPath("$.checksum").isNotEmpty())
                    .andExpect(jsonPath("$.confirmed").value(false))
                    .andExpect(jsonPath("$.confirmedBy").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.confirmedAt").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.createdAt").isNotEmpty());
        }

        @Test
        @DisplayName("검토자는 담당이 아니어도 조회할 수 있다")
        void allowsReviewer() throws Exception {
            long productId = createProduct();
            long documentId = upload(productId, pdf("설명서.pdf", "본문"), null);

            mockMvc.perform(asReviewer(get("/api/documents/{documentId}", documentId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.documentId").value((int) documentId));
        }

        @Test
        @DisplayName("없는 문서면 404 DOCUMENT_NOT_FOUND")
        void rejectsUnknownDocument() throws Exception {
            mockMvc.perform(asPm(get("/api/documents/{documentId}", 999_999L)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("DOCUMENT_NOT_FOUND"));
        }

        @Test
        @DisplayName("몇 번을 조회해도 extractStatus 가 변하지 않는다")
        void neverMutatesStatus() throws Exception {
            long productId = createProduct();
            long documentId = upload(productId, pdf("설명서.pdf", "본문"), null);
            ExtractStatus settled = awaitExtractionFinished(documentId);

            for (int i = 0; i < 5; i++) {
                mockMvc.perform(asPm(get("/api/documents/{documentId}", documentId)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.extractStatus").value(settled.name()));
            }

            assertThat(productDocumentRepository.findById(documentId).orElseThrow().getExtractStatus())
                    .isEqualTo(settled);
        }

        @Test
        @DisplayName("데모 헤더가 없으면 401")
        void requiresAuthentication() throws Exception {
            long productId = createProduct();
            long documentId = upload(productId, pdf("설명서.pdf", "본문"), null);

            mockMvc.perform(get("/api/documents/{documentId}", documentId))
                    .andExpect(status().isUnauthorized());
        }
    }
}
