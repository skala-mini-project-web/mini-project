package com.crosschecklab.domain.product;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;

import static com.crosschecklab.global.security.DemoAuthenticationFilter.ROLE_HEADER;
import static com.crosschecklab.global.security.DemoAuthenticationFilter.USER_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosschecklab.global.common.enums.ProductLifecycleStatus;
import com.crosschecklab.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
    private static final String OTHER_PM_USERNAME = "product_list_test_other_pm";

    @Autowired
    private JdbcTemplate jdbc;

    // 컨테이너는 JVM 당 하나라 여기서 만든 상품이 다른 테스트로 새어 나간다.
    // 테스트마다 앞뒤로 비워 개수를 단언할 수 있게 하고 잔여 데이터도 남기지 않는다.
    // 실행 경계에 묶인 Review는 append-only이므로 FK 전체를 함께 초기화한 뒤 참조 순서대로 지운다.
    @BeforeEach
    @AfterEach
    void clearProducts() {
        jdbc.execute("TRUNCATE TABLE analysis_executions CASCADE");
        jdbc.update("DELETE FROM analyses");
        jdbc.update("DELETE FROM product_documents");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM users WHERE username = ?", OTHER_PM_USERNAME);
    }

    // 분석은 문서에 달리므로 latestAnalysis 를 만들려면 문서가 먼저 있어야 한다.
    private long insertDocument(long productId) {
        return jdbc.queryForObject("""
                INSERT INTO product_documents
                    (product_id, file_name, media_type, storage_key, extract_status, extracted_text,
                     confirmed, created_at, updated_at)
                VALUES (?, '상품설명서.pdf', 'application/pdf', 'mock://documents/clean',
                        'READY', '확정된 추출 텍스트입니다.', TRUE, NOW(), NOW())
                RETURNING id""", Long.class, productId);
    }

    // red_team_pack_id 1 은 V2 시드 값이다. input_hash 는 (문서, 해시) 부분 UNIQUE 를 피하려고 호출부가 정한다.
    private long insertAnalysis(long documentId, String status, String inputHash) {
        return jdbc.queryForObject("""
                INSERT INTO analyses
                    (product_document_id, red_team_pack_id, status, progress, input_hash, created_at, updated_at)
                VALUES (?, 1, ?, 0, ?, NOW(), NOW())
                RETURNING id""", Long.class, documentId, status, inputHash);
    }

    private void insertReview(long analysisId, String reviewStatus) {
        Long executionId = jdbc.queryForObject("""
                INSERT INTO analysis_executions
                    (analysis_id, attempt_no, execution_token, status, retryable, retrieval_version,
                     provider_risk_score, model_version, prompt_version, started_at, finished_at, created_at)
                VALUES (?, 1, ?, 'SUCCEEDED', FALSE, 'product-api-test',
                        82, 'product-api-test', 'product-api-test', NOW(), NOW(), NOW())
                RETURNING id
                """, Long.class, analysisId, java.util.UUID.randomUUID().toString());
        jdbc.update("UPDATE analyses SET current_successful_execution_id = ? WHERE id = ?", executionId, analysisId);
        jdbc.update("""
                INSERT INTO reviews (analysis_id, analysis_execution_id, reviewer_id, status, created_at, updated_at)
                VALUES (?, ?, 2, ?, NOW(), NOW())
                """, analysisId, executionId, reviewStatus);
    }

    private long insertProduct(long ownerId, String name, String productType) {
        return jdbc.queryForObject("""
                INSERT INTO products (owner_id, name, product_type, created_at, updated_at)
                VALUES (?, ?, ?, NOW(), NOW())
                RETURNING id
                """, Long.class, ownerId, name, productType);
    }

    private JsonNode listProducts(MockHttpServletRequestBuilder request) throws Exception {
        return objectMapper.readTree(mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private List<Long> productIds(JsonNode response) {
        List<Long> ids = new ArrayList<>();
        response.path("items").forEach(item -> ids.add(item.path("productId").asLong()));
        return ids;
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
                    .andExpect(jsonPath("$.items[0].status").value("DRAFT"))
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.totalElements").value(2));
        }

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
                    .andExpect(jsonPath("$.items[0].status").value("DRAFT"))
                    .andExpect(jsonPath("$.items[1].productId").value(analyzed))
                    .andExpect(jsonPath("$.items[1].latestAnalysis.analysisId").value(latest))
                    .andExpect(jsonPath("$.items[1].latestAnalysis.status").value("IN_REVIEW"))
                    .andExpect(jsonPath("$.items[1].status").value("IN_REVIEW"));
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
        @DisplayName("전체 DB 를 필터링한 뒤 12개씩 순회해도 누락·중복 없이 정확한 합계를 반환한다")
        void filtersBeforePagingAcrossMoreThanTwentyProducts() throws Exception {
            List<Long> matching = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                matching.add(insertProduct(1, "Target 상품 " + i, "SAVINGS"));
            }
            for (int i = 0; i < 26; i++) {
                insertProduct(1, "검색 불일치 " + i, "SAVINGS");
                insertProduct(1, "Target 유형 불일치 " + i, "LOAN");
            }
            long analyzed = insertProduct(1, "Target 상태 불일치", "SAVINGS");
            insertAnalysis(insertDocument(analyzed), "COMPLETED", "filtered-analyzed");

            List<Long> traversed = new ArrayList<>();
            for (int page = 0; page < 4; page++) {
                JsonNode response = listProducts(asPm(get("/api/products")
                        .param("page", Integer.toString(page)).param("size", "12")
                        .param("q", "  tArGeT  ").param("productType", "SAVINGS").param("status", "DRAFT")));
                assertThat(response.path("totalElements").asLong()).isEqualTo(25);
                assertThat(response.path("totalPages").asInt()).isEqualTo(3);
                assertThat(response.path("page").asInt()).isEqualTo(page);
                assertThat(response.path("size").asInt()).isEqualTo(12);
                assertThat(response.path("items").size()).isEqualTo(page < 2 ? 12 : page == 2 ? 1 : 0);
                traversed.addAll(productIds(response));
            }
            assertThat(traversed).containsExactlyElementsOf(matching.reversed()).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("검색은 이름의 대소문자 무시 부분문자열 또는 상품 ID 부분문자열이다")
        void searchesPlainNamesAndIds() throws Exception {
            long matching = createProduct("프리미엄 Alpha 적금");
            createProduct("다른 예금");
            assertThat(productIds(listProducts(asPm(get("/api/products").param("q", "  aLPHa  ")))))
                    .containsExactly(matching);
            assertThat(productIds(listProducts(asPm(get("/api/products").param("q", "미엄")))))
                    .containsExactly(matching);
            String fragment = Long.toString(matching);
            fragment = fragment.substring(fragment.length() - 1);
            List<Long> expected = jdbc.queryForList("""
                    SELECT id FROM products WHERE strpos(CAST(id AS text), ?) > 0 ORDER BY id DESC
                    """, Long.class, fragment);
            assertThat(productIds(listProducts(asPm(get("/api/products").param("q", " " + fragment + " ")))))
                    .containsExactlyElementsOf(expected);
        }

        @ParameterizedTest
        @ValueSource(strings = {"%", "_", "\\", ".*", "[", "ㄱ.*", "' OR 1=1 --"})
        @DisplayName("LIKE 와 정규식 특수문자도 검색어에서는 리터럴이다")
        void treatsWildcardsAndRegexAsLiteralSubstrings(String query) throws Exception {
            long matching = createProduct("리터럴 " + query + " 상품");
            createProduct("일반 가나다 상품");
            JsonNode response = listProducts(asPm(get("/api/products").param("q", query)));
            assertThat(productIds(response)).containsExactly(matching);
            assertThat(response.path("totalElements").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("초성 검색만 ECMAScript 공백을 제거하고 일반 검색은 내부 공백을 보존한다")
        void matchesChoseongWithExactJavascriptWhitespace() throws Exception {
            String whitespace = "\t\n\u000B\f\r \u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005"
                    + "\u2006\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF";
            long matching = createProduct("스" + whitespace + "마" + whitespace + "트 인컴");
            long jamo = createProduct("ㅅㅁㅌ");
            createProduct("스\u0085마트");
            createProduct("스\u200B마트");
            createProduct("스\u180E마트");
            createProduct("스머프");

            JsonNode response = listProducts(asPm(get("/api/products")
                    .param("q", whitespace + "ㅅ" + whitespace + "ㅁㅌ" + whitespace)));
            assertThat(response.path("totalElements").asLong()).isEqualTo(2);
            assertThat(productIds(response)).containsExactly(jamo, matching);
            assertThat(productIds(listProducts(asPm(get("/api/products").param("q", "스 마 트"))))).isEmpty();
            assertThat(listProducts(asPm(get("/api/products").param("q", whitespace)))
                    .path("totalElements").asLong()).isEqualTo(6);
            for (String notJsWhitespace : List.of("\u0085", "\u200B", "\u180E")) {
                assertThat(productIds(listProducts(asPm(get("/api/products")
                        .param("q", "ㅅ" + notJsWhitespace + "ㅁㅌ"))))).isEmpty();
            }
        }

        @Test
        @DisplayName("ECMAScript 공백이 아닌 마지막 NEL 앞 공백은 trim 으로 지우지 않는다")
        void preservesWhitespaceBeforeTrailingNonJavascriptLineTerminator() throws Exception {
            long matching = createProduct("Alpha \u0085");
            createProduct("Alpha\u0085");
            assertThat(productIds(listProducts(asPm(get("/api/products").param("q", "Alpha \u0085")))))
                    .containsExactly(matching);
        }

        @ParameterizedTest
        @CsvSource({
                "ㄱ, 가, 깋", "ㄲ, 까, 낗", "ㄴ, 나, 닣", "ㄷ, 다, 딯", "ㄸ, 따, 띻",
                "ㄹ, 라, 맇", "ㅁ, 마, 밓", "ㅂ, 바, 빟", "ㅃ, 빠, 삫", "ㅅ, 사, 싷",
                "ㅆ, 싸, 앃", "ㅇ, 아, 잏", "ㅈ, 자, 짛", "ㅉ, 짜, 찧", "ㅊ, 차, 칳",
                "ㅋ, 카, 킿", "ㅌ, 타, 팋", "ㅍ, 파, 핗", "ㅎ, 하, 힣"
        })
        @DisplayName("19개 초성 각각의 첫·마지막 음절과 초성 자체만 매칭한다")
        void matchesAllChoseongRangeBoundaries(String consonant, String first, String last) throws Exception {
            long matching = createProduct(first + last);
            long literal = createProduct(consonant);
            createProduct(Character.toString((char) (first.charAt(0) - 1)));
            createProduct(Character.toString((char) (last.charAt(0) + 1)));
            JsonNode response = listProducts(asPm(get("/api/products").param("q", consonant)));
            assertThat(productIds(response)).containsExactly(literal, matching);
            assertThat(response.path("totalElements").asLong()).isEqualTo(2);
        }

        @Test
        @DisplayName("내용과 count 에 소유권을 적용하며 ownerId 요청 파라미터는 권한을 바꾸지 않는다")
        void isolatesOwnersForCombinedFiltersAndCounts() throws Exception {
            long otherPm = jdbc.queryForObject("""
                    INSERT INTO users (username, name, role, active, created_at, updated_at)
                    VALUES (?, '다른 담당자', 'PRODUCT_MANAGER', TRUE, NOW(), NOW())
                    RETURNING id
                    """, Long.class, OTHER_PM_USERNAME);
            long own = insertProduct(1, "공통 상품 본인", "LOAN");
            List<Long> foreign = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                foreign.add(insertProduct(otherPm, "공통 상품 타인 " + i, "LOAN"));
            }
            JsonNode manager = listProducts(asPm(get("/api/products")
                    .param("q", "ㄱㅌ").param("productType", "LOAN").param("status", "DRAFT")
                    .param("size", "1").param("ownerId", Long.toString(otherPm))));
            assertThat(productIds(manager)).containsExactly(own);
            assertThat(manager.path("totalElements").asLong()).isEqualTo(1);
            assertThat(manager.path("totalPages").asInt()).isEqualTo(1);

            JsonNode reviewer = listProducts(asReviewer(get("/api/products")
                    .param("q", "ㄱㅌ").param("productType", "LOAN").param("status", "DRAFT")
                    .param("size", "12").param("ownerId", PM_ID)));
            assertThat(productIds(reviewer)).containsExactlyElementsOf(foreign.reversed().subList(0, 12));
            assertThat(reviewer.path("totalElements").asLong()).isEqualTo(26);
            assertThat(reviewer.path("totalPages").asInt()).isEqualTo(3);

            mockMvc.perform(asPm(get("/api/products").param("q", "타인")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty())
                    .andExpect(jsonPath("$.totalElements").value(0));
        }

        @ParameterizedTest
        @CsvSource({
                "CREATED,,RUNNING", "CREATED,APPROVED,RUNNING",
                "RUNNING,,RUNNING", "RUNNING,REJECTED,RUNNING", "RUNNING,PENDING,RUNNING",
                "COMPLETED,,ANALYZED", "IN_REVIEW,,IN_REVIEW", "FAILED,,NEEDS_FIX",
                "COMPLETED,APPROVED,APPROVED", "IN_REVIEW,APPROVED,APPROVED", "FAILED,APPROVED,APPROVED",
                "COMPLETED,REJECTED,NEEDS_FIX", "IN_REVIEW,REJECTED,NEEDS_FIX",
                "COMPLETED,PENDING,IN_REVIEW", "FAILED,PENDING,IN_REVIEW"
        })
        @DisplayName("최신 분석과 그 검토 상태의 우선순위를 응답 및 모든 상태 필터에 동일하게 적용한다")
        void derivesLifecycleAndFilters(String analysisStatus, String reviewStatus, String lifecycle) throws Exception {
            long product = createProduct("상태 판정 상품");
            long analysis = insertAnalysis(insertDocument(product), analysisStatus, "lifecycle");
            if (reviewStatus != null) {
                insertReview(analysis, reviewStatus);
            }
            for (ProductLifecycleStatus filter : ProductLifecycleStatus.values()) {
                JsonNode response = listProducts(asPm(get("/api/products").param("status", filter.name())
                        .param("size", "1")));
                boolean matches = filter.name().equals(lifecycle);
                assertThat(response.path("totalElements").asLong()).isEqualTo(matches ? 1 : 0);
                assertThat(response.path("totalPages").asInt()).isEqualTo(matches ? 1 : 0);
                if (matches) {
                    assertThat(productIds(response)).containsExactly(product);
                    assertThat(response.at("/items/0/status").asText()).isEqualTo(lifecycle);
                    assertThat(response.at("/items/0/latestAnalysis/analysisId").asLong()).isEqualTo(analysis);
                    assertThat(response.at("/items/0/latestAnalysis/status").asText()).isEqualTo(analysisStatus);
                } else {
                    assertThat(productIds(response)).isEmpty();
                }
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"APPROVED", "REJECTED", "PENDING"})
        @DisplayName("새 분석은 이전 검토를 상속하지 않고 최신 문서와 무관하게 가장 큰 분석 ID 를 쓴다")
        void ignoresOlderReviewsAndSelectsLatestAnalysisAcrossAllDocuments(String olderReview) throws Exception {
            long product = createProduct("분석 재실행 상품");
            long olderDocument = insertDocument(product);
            long newerDocument = insertDocument(product);
            long oldAnalysis = insertAnalysis(newerDocument, "IN_REVIEW", "old-reviewed");
            insertReview(oldAnalysis, olderReview);
            long newestAnalysis = insertAnalysis(olderDocument, "COMPLETED", "newest-on-older-document");
            // 최신의 기준은 created_at 이 아니라 ID 다.
            jdbc.update("UPDATE analyses SET created_at = TIMESTAMPTZ '2000-01-01 00:00:00+00' WHERE id = ?",
                    newestAnalysis);
            jdbc.update("UPDATE products SET created_at = TIMESTAMPTZ '2026-09-08 10:14:15.123456+09' WHERE id = ?",
                    product);

            JsonNode response = listProducts(asPm(get("/api/products").param("status", "ANALYZED")));
            assertThat(productIds(response)).containsExactly(product);
            assertThat(response.at("/items/0/latestDocument/documentId").asLong()).isEqualTo(newerDocument);
            assertThat(response.at("/items/0/latestAnalysis/analysisId").asLong()).isEqualTo(newestAnalysis);
            assertThat(response.at("/items/0/status").asText()).isEqualTo("ANALYZED");
            assertThat(OffsetDateTime.parse(response.at("/items/0/createdAt").asText()).toInstant())
                    .isEqualTo(OffsetDateTime.parse("2026-09-08T10:14:15.123456+09:00").toInstant());
        }

        @ParameterizedTest
        @CsvSource({"productType, INSURANCE", "productType, savings", "status, COMPLETED", "status, approved"})
        @DisplayName("알 수 없는 필터 enum 은 400 VALIDATION_ERROR")
        void rejectsInvalidFilters(String parameter, String value) throws Exception {
            mockMvc.perform(asPm(get("/api/products").param(parameter, value)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(parameter));
        }

        @Test
        @DisplayName("size 가 100 을 넘거나 page 가 음수면 400")
        void rejectsOutOfRangePaging() throws Exception {
            mockMvc.perform(asPm(get("/api/products").param("size", "0")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

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
