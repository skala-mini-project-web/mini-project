package com.crosschecklab.global.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

// 데모 헤더 인증은 헤더만으로 사용자를 사칭할 수 있어 프로덕션에 딸려 나가면 안 된다.
// 경고 로그는 실수를 막지 못하므로 기동 자체를 거부하는지 확인한다.
@DisplayName("데모 인증 프로덕션 기동 가드")
class DemoAuthenticationGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {"prod", "production"})
    @DisplayName("프로덕션 프로파일에서는 기동을 거부한다")
    void rejectsProductionProfiles(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);

        assertThatIllegalStateException()
                .isThrownBy(() -> SecurityConfig.requireNonProductionProfile(environment))
                .withMessageContaining("프로덕션");
    }

    @Test
    @DisplayName("다른 프로파일과 함께 켜져 있어도 거부한다")
    void rejectsProductionAmongOtherProfiles() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("demo", "prod");

        assertThatIllegalStateException()
                .isThrownBy(() -> SecurityConfig.requireNonProductionProfile(environment));
    }

    @ParameterizedTest
    @ValueSource(strings = {"demo", "local", "test"})
    @DisplayName("데모·로컬 프로파일은 그대로 통과한다")
    void allowsNonProductionProfiles(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);

        assertThatCode(() -> SecurityConfig.requireNonProductionProfile(environment)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("프로파일을 지정하지 않아도 통과한다")
    void allowsDefaultProfile() {
        assertThatCode(() -> SecurityConfig.requireNonProductionProfile(new MockEnvironment()))
                .doesNotThrowAnyException();
    }
}
