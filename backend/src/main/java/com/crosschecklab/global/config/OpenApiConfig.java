package com.crosschecklab.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI guardLabOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("mini project MVP API")
                        .description("AI 기반 금융 상품 판매 리스크 사전 검증 Digital Twin 시뮬레이션 플랫폼 API")
                        .version("v0.3"))
                .components(new Components()
                        .addParameters("X-Demo-User-Id", new Parameter()
                                .in("header")
                                .name("X-Demo-User-Id")
                                .required(true)
                                .description("현재 데모 사용자 ID (users.id)")
                        )
                        .addParameters("X-Demo-Role", new Parameter()
                                .in("header")
                                .name("X-Demo-Role")
                                .required(true)
                                .description("PRODUCT_MANAGER 또는 COMPLIANCE_REVIEWER (서버가 DB 값으로 재검증)")
                        )
                        .addParameters("X-Trace-Id", new Parameter()
                                .in("header")
                                .name("X-Trace-Id")
                                .required(false)
                                .description("요청 추적 ID, 미전달 시 서버 생성")
                        )
                );
    }
}
