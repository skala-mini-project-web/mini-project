package com.crosschecklab.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crosschecklab.analysis.provider.ProviderException;
import com.crosschecklab.analysis.provider.dto.AnalysisRequest;
import com.crosschecklab.analysis.provider.dto.AnalysisResult;
import com.crosschecklab.analysis.provider.http.AiServiceProperties;
import com.crosschecklab.analysis.provider.http.HttpRiskAnalysisProvider;
import com.crosschecklab.global.common.enums.EvidenceSourceType;
import com.crosschecklab.global.common.enums.PersonaCode;
import com.crosschecklab.global.common.enums.RedTeamRuleCode;
import com.crosschecklab.global.common.enums.Severity;
import com.crosschecklab.global.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// ai-service HTTP 어댑터 검증. 실제 ai-service 대신 JDK 내장 HTTP 서버로 응답을 흉내낸다.
// 검증 대상은 요청 직렬화, 응답 역직렬화, 그리고 오류 → errorCode/retryable 매핑이다.
class HttpRiskAnalysisProviderTest {

    private static final String SUCCESS_BODY = """
            {"riskScore":82,"modelVersion":"mock-risk-v1","promptVersion":"mock-prompt-v1",
             "findings":[{"statement":"안정성 표현이 원금보장으로 오인될 가능성이 있습니다.","severity":"HIGH",
             "affectedPersonaCodes":["FINANCIAL_BEGINNER"],
             "evidenceReferences":[{"evidenceDocumentId":1,"excerpt":"원금손실 가능성은 인접 표시"}],
             "recommendation":"원금 손실 가능성을 명시하세요."}]}""";

    private HttpServer server;
    private final AtomicReference<String> capturedRequest = new AtomicReference<>();
    private int status = 200;
    private String responseBody = SUCCESS_BODY;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/v1/risk-analyses", exchange -> {
            capturedRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private HttpRiskAnalysisProvider provider() {
        return provider("http://localhost:" + server.getAddress().getPort());
    }

    private HttpRiskAnalysisProvider provider(String baseUrl) {
        return new HttpRiskAnalysisProvider(
                new AiServiceProperties(baseUrl, Duration.ofMillis(500), Duration.ofSeconds(2), "SCENARIO"),
                new ObjectMapper());
    }

    private AnalysisRequest request() {
        return new AnalysisRequest(1L, "GUARANTEE_MISUNDERSTANDING_HIGH", "확정된 상품 설명 텍스트",
                List.of(PersonaCode.FINANCIAL_BEGINNER), "CORE_FINANCIAL_RISK_V1",
                List.of(RedTeamRuleCode.STABILITY_KEYWORD),
                List.of(new AnalysisRequest.EvidenceDocumentPayload(1L, EvidenceSourceType.INTERNAL_POLICY,
                        "내부준칙", "원금손실 가능성은 인접 표시")));
    }

    @Test
    @DisplayName("정상 응답을 AnalysisResult 로 읽고, 요청은 ai-service 계약 필드로 직렬화된다")
    void success() {
        AnalysisResult result = provider().analyze(request());

        assertThat(result.riskScore()).isEqualTo(82);
        assertThat(result.modelVersion()).isEqualTo("mock-risk-v1");
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(Severity.HIGH);
            assertThat(finding.affectedPersonaCodes()).containsExactly(PersonaCode.FINANCIAL_BEGINNER);
            assertThat(finding.evidenceReferences()).isNotEmpty();
        });
        // ai-service 는 extra="forbid" 라 필드명이 정확히 일치해야 한다.
        assertThat(capturedRequest.get())
                .contains("\"analysisId\":1", "\"scenarioCode\"", "\"confirmedText\"", "\"personaCodes\"",
                        "\"redTeamPackCode\"", "\"ruleCodes\"", "\"evidenceDocuments\"", "\"sourceType\"");
    }

    @Test
    @DisplayName("503 + retryable=true → AI_SERVICE_TEMPORARY_FAILURE, 재시도 가능")
    void temporaryFailure() {
        status = 503;
        responseBody = """
                {"errorCode":"AI_SERVICE_TEMPORARY_FAILURE","message":"temporarily unavailable","retryable":true}""";

        assertThatThrownBy(() -> provider().analyze(request()))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_SERVICE_TEMPORARY_FAILURE);
                    assertThat(e.isRetryable()).isTrue();
                });
    }

    @Test
    @DisplayName("500 + retryable=false → PROVIDER_RESPONSE_INVALID, 재시도 불가")
    void invalidResponse() {
        status = 500;
        responseBody = """
                {"errorCode":"PROVIDER_RESPONSE_INVALID","message":"invalid","retryable":false}""";

        assertThatThrownBy(() -> provider().analyze(request()))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROVIDER_RESPONSE_INVALID);
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    @DisplayName("HIGH Finding 에 근거가 없으면 계약 위반으로 끊는다 (재시도 불가)")
    void highFindingWithoutEvidence() {
        responseBody = """
                {"riskScore":82,"modelVersion":"m","promptVersion":"p",
                 "findings":[{"statement":"s","severity":"HIGH","affectedPersonaCodes":["SENIOR"],
                 "evidenceReferences":[],"recommendation":null}]}""";

        assertThatThrownBy(() -> provider().analyze(request()))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROVIDER_RESPONSE_INVALID);
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    @DisplayName("연결 실패는 재시도 가능한 일시 장애로 매핑된다")
    void connectionFailure() {
        assertThatThrownBy(() -> provider("http://localhost:1").analyze(request()))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_SERVICE_TEMPORARY_FAILURE);
                    assertThat(e.isRetryable()).isTrue();
                });
    }
}
