package com.crosschecklab.global.config;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

// JPA Auditing 활성화
// 기본 DateTimeProvider는 LocalDateTime을 주므로 TIMESTAMPTZ 컬럼과 맞는 OffsetDateTime 을 돌려주는 provider 를 따로 등록
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaConfig {

    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(OffsetDateTime.now(clock));
    }
}
