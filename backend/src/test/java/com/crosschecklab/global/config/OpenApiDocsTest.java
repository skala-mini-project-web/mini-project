package com.crosschecklab.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crosschecklab.support.IntegrationTestSupport;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 생성된 OpenAPI 문서를 직접 읽어 Swagger UI 에 헤더 입력칸이 실제로 생기는지 확인한다.
// components 정의만 있고 $ref 가 빠지면 UI 에 아무것도 안 뜨는데, 이 회귀를 눈으로만 잡기 어렵다.
@DisplayName("OpenAPI 문서의 데모 인증 헤더 노출")
class OpenApiDocsTest extends IntegrationTestSupport {

    private static final String PRODUCTS_GET = "$.paths['/api/products'].get.parameters";
    private static final String DEMO_SESSION_POST = "$.paths['/api/demo/session'].post.parameters";

    @Test
    @DisplayName("인증이 필요한 오퍼레이션에 데모 헤더가 필수로 붙는다")
    void exposesDemoHeadersOnAuthenticatedOperations() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(PRODUCTS_GET + "[*].$ref",
                        Matchers.hasItems("#/components/parameters/X-Demo-User-Id",
                                "#/components/parameters/X-Demo-Role")))
                .andExpect(jsonPath("$.components.parameters['X-Demo-User-Id'].in").value("header"))
                .andExpect(jsonPath("$.components.parameters['X-Demo-User-Id'].required").value(true))
                .andExpect(jsonPath("$.components.parameters['X-Demo-Role'].required").value(true));
    }

    @Test
    @DisplayName("인증이 필요 없는 데모 세션 발급에는 데모 헤더가 붙지 않는다")
    void omitsDemoHeadersOnPublicOperation() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(DEMO_SESSION_POST + "[*].$ref",
                        Matchers.not(Matchers.hasItem("#/components/parameters/X-Demo-User-Id"))))
                .andExpect(jsonPath(DEMO_SESSION_POST + "[*].$ref",
                        Matchers.not(Matchers.hasItem("#/components/parameters/X-Demo-Role"))));
    }

    @Test
    @DisplayName("선택 헤더인 X-Trace-Id 는 인증 여부와 무관하게 모든 오퍼레이션에 붙는다")
    void exposesTraceIdEverywhere() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(PRODUCTS_GET + "[*].$ref",
                        Matchers.hasItem("#/components/parameters/X-Trace-Id")))
                .andExpect(jsonPath(DEMO_SESSION_POST + "[*].$ref",
                        Matchers.hasItem("#/components/parameters/X-Trace-Id")))
                .andExpect(jsonPath("$.components.parameters['X-Trace-Id'].required").value(false));
    }

    // DemoUser 는 리졸버가 채우는 값이라 요청에 실리지 않는다.
    // 무시 설정이 빠지면 currentUser 가 required 쿼리 파라미터로 새어 나와,
    // Swagger UI 가 사용자에게 DemoUser 객체를 직접 입력하라고 요구한다.
    @Test
    @DisplayName("리졸버가 채우는 DemoUser 가 쿼리 파라미터로 새어 나오지 않는다")
    void hidesResolverSuppliedDemoUser() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(PRODUCTS_GET + "[*].name",
                        Matchers.not(Matchers.hasItem("currentUser"))))
                .andExpect(jsonPath("$.components.schemas.DemoUser").doesNotExist());
    }
}
