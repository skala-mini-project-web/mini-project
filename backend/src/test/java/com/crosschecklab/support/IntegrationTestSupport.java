package com.crosschecklab.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Postgres Testcontainer 기반 통합 테스트 베이스.
 *
 * <p>컨테이너는 JVM당 한 번만 기동하는 싱글턴이며 전체 테스트 클래스가 공유한다.
 * Flyway가 매 컨텍스트마다 V1/V2를 적용하므로 실제 스키마·시드 데이터로 검증할 수 있다.
 *
 * <p>트랙 A·B 공용이다. 도메인별 픽스처는 각 테스트 클래스에서 준비한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTestSupport {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected MockMvc mockMvc;
}
