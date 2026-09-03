package com.crosschecklab.domain.product;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;

import static com.crosschecklab.global.security.DemoAuthenticationFilter.ROLE_HEADER;
import static com.crosschecklab.global.security.DemoAuthenticationFilter.USER_ID_HEADER;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosschecklab.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// V2 시드: 1 = pm_park(PRODUCT_MANAGER), 2 = reviewer_kim(COMPLIANCE_REVIEWER)
// products 는 시드에 없으므로 각 테스트가 API 로 직접 만든다.
@DisplayName("PROD-001/002 상품 API")
class ProductApiTest extends IntegrationTestSupport {

    private static final String PM_ID = "1";
    private static final String REVIEWER_ID = "2";

    @Autowired
    private JdbcTemplate jdbc;

    // 컨테이너는 JVM 당 하나라 여기서 만든 상품이 다른 테스트로 새어 나간다.
    // 테스트마다 앞뒤로 비워 개수를 단언할 수 있게 하고 잔여 데이터도 남기지 않는다.
    // 참조 순서대로 지운다 (analyses → product_documents → products).
    @BeforeEach
    @AfterEach
    void clearProducts() {
        jdbc.update("DELETE FROM analyses");
        jdbc.update("DELETE FROM product_documents");
        jdbc.update("DELETE FROM products");
    }

    /**
     * 테스트용 문서를 직접 DB에 삽입한다.
     * 분석은 문서에 달리므로 latestAnalysis 를 만들려면 문서가 먼저 있어야 한다.
     *
     * @param productId 문서를 등록할 상품 ID
     * @return 생성된 문서 ID
     */
    private long insertDocument(long productId) {
        return jdbc.queryForObject("""
                INSERT INTO product_documents
                    (product_id, file_name, media_type, storage_key, extract_status, extracted_text,
                     confirmed, created_at, updated_at)
                VALUES (?, '상품설명서.pdf', 'application/pdf', 'mock://documents/clean',
                        'READY', '확정된 추출 텍스트입니다.', TRUE, NOW(), NOW())
                RETURNING id""", Long.class, productId);
    }

    /**
     * 테스트용 분석 레코드를 직접 DB에 삽입한다.
     * red_team_pack_id 1 은 V2 시드 값이다. input_hash 는 (문서, 해시) 부분 UNIQUE 를 피하려고 호출부가 정한다.
     *
     * @param documentId 분석 대상 문서 ID
     * @param status 분석 상태 (AnalysisStatus enum 값의 문자열)
     * @param inputHash 중복 방지용 입력 해시 (테스트별로 고유한 값 사용)
     * @return 생성된 분석 ID
     */
    private long insertAnalysis(long documentId, String status, String inputHash) {
        return jdbc.queryForObject("""
                INSERT INTO analyses
                    (product_document_id, red_team_pack_id, status, progress, input_hash, created_at, updated_at)
                VALUES (?, 1, ?, 0, ?, NOW(), NOW())
                RETURNING id""", Long.class, documentId, status, inputHash);
    }

    private MockHttpServletRequestBuilder asPm(MockHttpServletRequestBuilder builder) {
        return builder.header(USER_ID_HEADER, PM_ID).header(ROLE_HEADER, "PRODUCT_MANAGER");
    }

    private MockHttpServletRequestBuilder asReviewer(MockHttpServletRequestBuilder builder) {
        return builder.header(USER_ID_HEADER, REVIEWER_ID).header(ROLE_HEADER, "COMPLIANCE_REVIEWER");
    }

    private MockHttpServletRequestBuilder createRequest(Map<String, Object> body) throws Exception {
        return post("/api/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    // 테스트마다 상품을 하나 만들고 productId 를 돌려준다.
    private long createProduct(String name) throws Exception {
        String response = mockMvc.perform(asPm(createRequest(
                        Map.of("name", name, "productType", "INVESTMENT", "description", "테스트 상품"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(response);
        return node.get("productId").asLong();
    }

    @Nested
    @DisplayName("POST /api/products")
    class Create {

        @Test
        @DisplayName("담당자가 등록하면 201 과 Location 헤더를 반환하고 소유자는 요청 사용자로 지정된다")
        void createsProduct() throws Exception {
            mockMvc.perform(asPm(createRequest(
                            Map.of("name", "행복드림 ELS 12호", "productType", "INVESTMENT", "description", "원금 비보장형"))))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern("/api/products/\\d+")))
                    .andExpect(jsonPath("$.productId").isNumber())
                    .andExpect(jsonPath("$.name").value("행복드림 ELS 12호"))
                    .andExpect(jsonPath("$.productType").value("INVESTMENT"))
                    .andExpect(jsonPath("$.ownerId").value(1))
                    .andExpect(jsonPath("$.ownerName").value("박서준 대리"))
                    // 갓 만든 상품이라 문서도 분석도 없다.
                    // 키를 빼는 게 아니라 명시적 null 로 내리는 것이 계약이므로 존재와 null 을 나눠 단언한다.
                    // (doesNotExist() 는 값이 null 이어도 통과해 이 구분을 못 잡는다)
                    .andExpect(jsonPath("$").value(allOf(hasKey("latestDocument"), hasKey("latestAnalysis"))))
                    .andExpect(jsonPath("$.latestDocument").value(nullValue()))
                    .andExpect(jsonPath("$.latestAnalysis").value(nullValue()))
                    .andExpect(jsonPath("$.createdAt").isNotEmpty());
        }

        @Test
        @DisplayName("description 은 생략할 수 있다")
        void allowsMissingDescription() throws Exception {
            mockMvc.perform(asPm(createRequest(Map.of("name", "설명 없는 상품", "productType", "SAVINGS"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$").value(hasKey("description")))
                    .andExpect(jsonPath("$.description").value(nullValue()));
        }

        @Test
        @DisplayName("name 이 없으면 400 VALIDATION_ERROR")
        void rejectsMissingName() throws Exception {
            mockMvc.perform(asPm(createRequest(Map.of("productType", "LOAN"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
        }

        @Test
        @DisplayName("name 이 100자를 넘으면 400")
        void rejectsTooLongName() throws Exception {
            mockMvc.perform(asPm(createRequest(
                            Map.of("name", "가".repeat(101), "productType", "INVESTMENT"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
        }

        @Test
        @DisplayName("description 이 500자를 넘으면 400")
        void rejectsTooLongDescription() throws Exception {
            mockMvc.perform(asPm(createRequest(
                            Map.of("name", "정상 상품", "productType", "INVESTMENT", "description", "가".repeat(501)))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("description"));
        }

        @Test
        @DisplayName("정의되지 않은 productType 은 400")
        void rejectsUnknownProductType() throws Exception {
            mockMvc.perform(asPm(createRequest(Map.of("name", "정상 상품", "productType", "INSURANCE"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("검토자는 상품을 등록할 수 없다 — 403 FORBIDDEN")
        void rejectsReviewer() throws Exception {
            mockMvc.perform(asReviewer(createRequest(Map.of("name", "검토자 상품", "productType", "INVESTMENT"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        }

        @Test
        @DisplayName("데모 헤더가 없으면 401")
        void requiresDemoHeaders() throws Exception {
            mockMvc.perform(createRequest(Map.of("name", "익명 상품", "productType", "INVESTMENT")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("DEMO_AUTHENTICATION_REQUIRED"));
        }

        @Test
        @DisplayName("요청 본문의 ownerId 는 무시하고 인증된 사용자를 소유자로 쓴다")
        void ignoresOwnerIdInBody() throws Exception {
            Map<String, Object> body = new HashMap<>();
            body.put("name", "소유자 위조 시도");
            body.put("productType", "INVESTMENT");
            body.put("ownerId", 2);

            mockMvc.perform(asPm(createRequest(body)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ownerId").value(1));
        }
    }

    @Nested
    @DisplayName("GET /api/products/{productId}")
    class FindById {

        @Test
        @DisplayName("소유자는 자기 상품을 조회할 수 있고 문서가 없으면 latestDocument 는 null 이다")
        void allowsOwner() throws Exception {
            long productId = createProduct("소유자 조회용 상품");

            mockMvc.perform(asPm(get("/api/products/{productId}", productId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.productId").value(productId))
                    .andExpect(jsonPath("$.name").value("소유자 조회용 상품"))
                    .andExpect(jsonPath("$").value(allOf(hasKey("latestDocument"), hasKey("latestAnalysis"))))
                    .andExpect(jsonPath("$.latestDocument").value(nullValue()))
                    .andExpect(jsonPath("$.latestAnalysis").value(nullValue()));
        }

        /**
         * 상품 상세 조회 시 분석 이력이 있을 때 최신 분석 정보가 응답에 포함되는지 검증한다.
         * 같은 문서에 여러 분석이 있으면 id가 가장 큰(최신) 분석을 반환한다.
         */
        @Test
        @DisplayName("분석 이력이 있으면 가장 최근 분석 하나가 latestAnalysis 로 나온다")
        void includesLatestAnalysis() throws Exception {
            long productId = createProduct("분석까지 마친 상품");
            long documentId = insertDocument(productId);
            insertAnalysis(documentId, "FAILED", "hash-old");
            long latest = insertAnalysis(documentId, "COMPLETED", "hash-new");

            mockMvc.perform(asPm(get("/api/products/{productId}", productId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.latestDocument.documentId").value(documentId))
                    .andExpect(jsonPath("$.latestAnalysis.analysisId").value(latest))
                    .andExpect(jsonPath("$.latestAnalysis.status").value("COMPLETED"));
        }

        /**
         * 상품에 문서가 여러 개 있고 각각에 분석 이력이 있을 때,
         * 모든 문서를 통틀어 상품 단위로 가장 최신 분석만 응답에 포함되는지 검증한다.
         */
        @Test
        @DisplayName("문서를 여러 번 올렸어도 상품 단위로 최신 분석 하나만 나온다")
        void picksLatestAnalysisAcrossDocuments() throws Exception {
            long productId = createProduct("재업로드한 상품");
            insertAnalysis(insertDocument(productId), "COMPLETED", "hash-first");
            long latest = insertAnalysis(insertDocument(productId), "RUNNING", "hash-second");

            mockMvc.perform(asPm(get("/api/products/{productId}", productId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.latestAnalysis.analysisId").value(latest))
                    .andExpect(jsonPath("$.latestAnalysis.status").value("RUNNING"));
        }

        @Test
        @DisplayName("검토자는 담당이 아닌 상품도 조회할 수 있다")
        void allowsReviewer() throws Exception {
            long productId = createProduct("검토자 조회용 상품");

            mockMvc.perform(asReviewer(get("/api/products/{productId}", productId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.productId").value(productId))
                    .andExpect(jsonPath("$.ownerId").value(1));
        }

        @Test
        @DisplayName("존재하지 않는 상품은 404 PRODUCT_NOT_FOUND")
        void rejectsUnknownProduct() throws Exception {
            mockMvc.perform(asPm(get("/api/products/{productId}", 999999)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));
        }

        @Test
        @DisplayName("데모 헤더가 없으면 401")
        void requiresDemoHeaders() throws Exception {
            long productId = createProduct("익명 조회용 상품");

            mockMvc.perform(get("/api/products/{productId}", productId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api/products")
    class FindPage {

        @Test
        @DisplayName("등록한 상품이 최신순으로 목록에 나온다")
        void listsOwnProducts() throws Exception {
            createProduct("첫 번째 상품");
            long second = createProduct("두 번째 상품");

            mockMvc.perform(asPm(get("/api/products")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(2))
                    .andExpect(jsonPath("$.items[0].productId").value(second))
                    .andExpect(jsonPath("$.items[0].name").value("두 번째 상품"))
                    .andExpect(jsonPath("$.items[0].ownerName").value("박서준 대리"))
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.totalElements").value(2));
        }

        /**
         * 상품 목록 조회 시 각 상품의 최신 분석 정보가 포함되는지 검증한다.
         * 분석 이력이 없는 상품은 latestAnalysis 필드가 null로 응답된다.
         */
        @Test
        @DisplayName("목록에도 상품별 최신 분석이 채워지고, 분석이 없는 상품은 null 이다")
        void listsLatestAnalysisPerProduct() throws Exception {
            long analyzed = createProduct("분석한 상품");
            long latest = insertAnalysis(insertDocument(analyzed), "IN_REVIEW", "hash-list");
            long untouched = createProduct("아직 분석하지 않은 상품");

            mockMvc.perform(asPm(get("/api/products")))
                    .andExpect(status().isOk())
                    // 최신순이라 나중에 만든 상품이 앞에 온다.
                    .andExpect(jsonPath("$.items[0].productId").value(untouched))
                    .andExpect(jsonPath("$.items[0]").value(hasKey("latestAnalysis")))
                    .andExpect(jsonPath("$.items[0].latestAnalysis").value(nullValue()))
                    .andExpect(jsonPath("$.items[1].productId").value(analyzed))
                    .andExpect(jsonPath("$.items[1].latestAnalysis.analysisId").value(latest))
                    .andExpect(jsonPath("$.items[1].latestAnalysis.status").value("IN_REVIEW"));
        }

        @Test
        @DisplayName("검토자는 담당이 아닌 상품까지 전부 본다")
        void reviewerSeesAllProducts() throws Exception {
            createProduct("담당자 상품");

            mockMvc.perform(asReviewer(get("/api/products")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.items[0].ownerId").value(1));
        }

        @Test
        @DisplayName("page / size 로 페이징된다")
        void paginates() throws Exception {
            createProduct("상품 A");
            createProduct("상품 B");
            createProduct("상품 C");

            mockMvc.perform(asPm(get("/api/products").param("page", "1").param("size", "2")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.size").value(2))
                    .andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.totalPages").value(2));
        }

        @Test
        @DisplayName("size 가 100 을 넘거나 page 가 음수면 400")
        void rejectsOutOfRangePaging() throws Exception {
            mockMvc.perform(asPm(get("/api/products").param("size", "101")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

            mockMvc.perform(asPm(get("/api/products").param("page", "-1")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("데모 헤더가 없으면 401")
        void requiresDemoHeaders() throws Exception {
            mockMvc.perform(get("/api/products"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
