package com.crosschecklab.domain.product;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// V2 시드: 1 = pm_park(PRODUCT_MANAGER), 2 = reviewer_kim(COMPLIANCE_REVIEWER)
// products 는 시드에 없으므로 각 테스트가 API 로 직접 만든다.
@DisplayName("PROD-001/002 상품 API")
class ProductApiTest extends IntegrationTestSupport {

    private static final String PM_ID = "1";
    private static final String REVIEWER_ID = "2";

    @Autowired
    private ProductRepository productRepository;

    // 컨테이너는 JVM 당 하나라 여기서 만든 상품이 다른 테스트로 새어 나간다.
    // 테스트마다 앞뒤로 비워 개수를 단언할 수 있게 하고 잔여 데이터도 남기지 않는다.
    @BeforeEach
    @AfterEach
    void clearProducts() {
        productRepository.deleteAll();
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
                    .andExpect(jsonPath("$.latestDocument").doesNotExist())
                    .andExpect(jsonPath("$.latestAnalysis").doesNotExist())
                    .andExpect(jsonPath("$.createdAt").isNotEmpty());
        }

        @Test
        @DisplayName("description 은 생략할 수 있다")
        void allowsMissingDescription() throws Exception {
            mockMvc.perform(asPm(createRequest(Map.of("name", "설명 없는 상품", "productType", "SAVINGS"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.description").doesNotExist());
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
                    .andExpect(jsonPath("$.latestDocument").doesNotExist())
                    .andExpect(jsonPath("$.latestAnalysis").doesNotExist());
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
