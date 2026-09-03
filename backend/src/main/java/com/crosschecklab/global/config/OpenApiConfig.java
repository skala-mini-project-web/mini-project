package com.crosschecklab.global.config;

import com.crosschecklab.global.security.CurrentUser;
import com.crosschecklab.global.security.DemoUser;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.Arrays;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;

@Configuration
public class OpenApiConfig {

    private static final String USER_ID_PARAMETER_REF = "#/components/parameters/X-Demo-User-Id";
    private static final String ROLE_PARAMETER_REF = "#/components/parameters/X-Demo-Role";
    private static final String TRACE_ID_PARAMETER_REF = "#/components/parameters/X-Trace-Id";

    static {
        // DemoUser 는 CurrentUserArgumentResolver 가 채우는 값이라 요청에 실리지 않는다.
        // 막아두지 않으면 record 컴포넌트(id/username/name/role)가 쿼리 파라미터로 문서에 새어 나온다.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(DemoUser.class);
    }

    @Bean
    public OpenAPI guardLabOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("mini project MVP API")
                        .description("AI 기반 금융 상품 판매 리스크 사전 검증 Digital Twin 시뮬레이션 플랫폼 API")
                        .version("v0.3"))
                .components(new Components()
                        // schema 가 없으면 OpenAPI 스펙상 불완전한 Parameter 라
                        // Swagger UI 가 입력칸 타입을 알 수 없다. users.id 는 bigint 라 int64.
                        .addParameters("X-Demo-User-Id", new Parameter()
                                .in("header")
                                .name("X-Demo-User-Id")
                                .required(true)
                                .schema(new IntegerSchema().format("int64"))
                                .description("현재 데모 사용자 ID (users.id)")
                        )
                        .addParameters("X-Demo-Role", new Parameter()
                                .in("header")
                                .name("X-Demo-Role")
                                .required(true)
                                .schema(new StringSchema())
                                .description("PRODUCT_MANAGER 또는 COMPLIANCE_REVIEWER (서버가 DB 값으로 재검증)")
                        )
                        .addParameters("X-Trace-Id", new Parameter()
                                .in("header")
                                .name("X-Trace-Id")
                                .required(false)
                                .schema(new StringSchema())
                                .description("요청 추적 ID, 미전달 시 서버 생성")
                        )
                );
    }

    // components 에 정의만 해두면 문서에 나타나지 않는다. 오퍼레이션마다 $ref 를 붙여야
    // Swagger UI 에 입력칸이 생기고, 그래야 "Try it out" 이 401 로 떨어지지 않는다.
    @Bean
    public OperationCustomizer demoHeaderCustomizer() {
        return (operation, handlerMethod) -> {
            if (requiresDemoUser(handlerMethod.getMethodParameters())) {
                operation.addParametersItem(new Parameter().$ref(USER_ID_PARAMETER_REF));
                operation.addParametersItem(new Parameter().$ref(ROLE_PARAMETER_REF));
            }
            // 추적 헤더는 선택값이라 인증 여부와 무관하게 모든 오퍼레이션에 붙인다.
            operation.addParametersItem(new Parameter().$ref(TRACE_ID_PARAMETER_REF));
            return operation;
        };
    }

    // SecurityConfig 는 전 요청 permitAll 이고 401 은 CurrentUserArgumentResolver 가 던진다.
    // 그래서 "@CurrentUser DemoUser 를 받는가" 가 인증 필수 여부와 정확히 같은 조건이다.
    private boolean requiresDemoUser(MethodParameter[] parameters) {
        return Arrays.stream(parameters)
                .anyMatch(parameter -> parameter.hasParameterAnnotation(CurrentUser.class)
                        && DemoUser.class.isAssignableFrom(parameter.getParameterType()));
    }
}
