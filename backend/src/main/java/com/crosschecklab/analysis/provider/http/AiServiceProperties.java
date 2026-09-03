package com.crosschecklab.analysis.provider.http;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// ai-service 연동 설정. Provider 교체 시 이 prefix 만 바꾸면 된다.
// 설정이 비어도 뜨도록 기본값을 둔다 (테스트 프로파일 등).
@ConfigurationProperties(prefix = "ai-service")
public record AiServiceProperties(
        @DefaultValue("http://localhost:8000") String baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("20s") Duration readTimeout,
        @DefaultValue("GUARANTEE_MISUNDERSTANDING_HIGH") String defaultScenarioCode
) {
}
