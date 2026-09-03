package com.crosschecklab.global.config;

import com.crosschecklab.domain.user.UserRepository;
import com.crosschecklab.global.security.DemoAuthenticationFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

// MVP 용 최소 보안 설정.
// 로그인/세션 대신 X-Demo-User-Id + X-Demo-Role 헤더로 사용자를 식별하므로
// 폼로그인/HTTP Basic 은 끄고 전 요청 permitAll 로 통과시킨다 (안 그러면 모든 요청이 401).
// 인가 로직은 아니며, 실제 RBAC 는 각 도메인이 DB role/소유권으로 판정
//
// !! 데모 전용 !! 헤더만으로 사용자를 사칭할 수 있으므로 외부에 노출된 환경에 배포하면 안 된다.
// API 명세 §1 의 MVP 인증 방식이며, 실제 배포 시에는 이 필터를 서명된 토큰(JWT/OAuth2) 기반
// 인증으로 교체해야 한다. 교체 지점은 DemoAuthenticationFilter 하나뿐이다.
@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   UserRepository userRepository) throws Exception {
        log.warn("데모 인증이 활성화되어 있습니다. X-Demo-User-Id 헤더만으로 사용자를 사칭할 수 있으므로 "
                + "외부에 노출된 환경에 배포하지 마세요. (실배포 시 서명된 토큰 인증으로 교체 필요)");

        return http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().permitAll())
                // SecurityContextHolderFilter 가 컨텍스트를 비운 뒤에 넣어야 인증이 살아남는다.
                // (체인 밖 서블릿 필터로 등록하면 여기서 덮어써진다)
                .addFilterAfter(new DemoAuthenticationFilter(userRepository), SecurityContextHolderFilter.class)
                .build();
    }
}
