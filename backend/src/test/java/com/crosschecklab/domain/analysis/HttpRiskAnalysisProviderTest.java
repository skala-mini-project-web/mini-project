package com.crosschecklab.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
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
             "retrievedContextChunkIds":[11],
             "knownFactIds":[7],
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
        return provider(baseUrl, false);
    }

    private HttpRiskAnalysisProvider provider(String baseUrl, boolean allowInsecureHttp) {
        return new HttpRiskAnalysisProvider(
                new AiServiceProperties(baseUrl, Duration.ofMillis(500), Duration.ofSeconds(2),
                        "SCENARIO", allowInsecureHttp),
                new ObjectMapper());
    }

    private AnalysisRequest request() {
        return new AnalysisRequest(1L, "GUARANTEE_MISUNDERSTANDING_HIGH", "확정된 상품 설명 텍스트",
                List.of(PersonaCode.FINANCIAL_BEGINNER), "CORE_FINANCIAL_RISK_V1",
                List.of(RedTeamRuleCode.STABILITY_KEYWORD),
                List.of(1L),
                List.of(new AnalysisRequest.RetrievedContextPayload(
                        11L, 1L, EvidenceSourceType.INTERNAL_POLICY, "내부준칙",
                        "원금손실 가능성은 인접 표시", 1, 0.91)),
                List.of(new AnalysisRequest.KnownFactPayload(7L, "확정된 공식 사실")));
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
            assertThat(finding.retrievedContextChunkIds()).containsExactly(11L);
            assertThat(finding.knownFactIds()).containsExactly(7L);
        });
        // ai-service 는 extra="forbid" 라 필드명이 정확히 일치해야 한다.
        assertThat(capturedRequest.get())
                .contains("\"analysisId\":1", "\"scenarioCode\"", "\"confirmedText\"", "\"personaCodes\"",
                        "\"redTeamPackCode\"", "\"ruleCodes\"", "\"selectedEvidenceDocumentIds\":[1]",
                        "\"retrievedContexts\"", "\"chunkId\":11", "\"evidenceDocumentId\":1",
                        "\"sourceType\"", "\"chunkText\":\"원금손실 가능성은 인접 표시\"");
        assertThat(capturedRequest.get()).contains("\"knownFacts\"", "\"factId\":7");
        assertThat(capturedRequest.get()).doesNotContain("\"evidenceDocuments\"", "\"content\"");
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
                 "retrievedContextChunkIds":[],"recommendation":null}]}""";

        assertThatThrownBy(() -> provider().analyze(request()))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROVIDER_RESPONSE_INVALID);
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    @DisplayName("loopback 이 아닌 http base-url 은 기동 시점에 거부된다")
    void plaintextBaseUrlIsRejected() {
        assertThatThrownBy(() -> provider("http://ai.example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https");
    }

    @Test
    @DisplayName("https 와 로컬 http 는 허용된다")
    void secureOrLocalBaseUrlIsAllowed() {
        assertThatNoException().isThrownBy(() -> provider("https://ai.example.com"));
        assertThatNoException().isThrownBy(() -> provider("http://127.0.0.1:8000"));
    }

    @Test
    @DisplayName("명시적으로 허용하면 로컬 Compose 서비스명의 http base-url을 사용할 수 있다")
    void composePlaintextBaseUrlIsAllowedOnlyByOptIn() {
        assertThatNoException().isThrownBy(() -> provider("http://ai-service:8000", true));
    }

    @Test
    @DisplayName("요청에서 검색되지 않은 근거 청크를 인용하면 계약 위반으로 끊는다")
    void contextChunkOutsideRetrievedContextsIsRejected() {
        // 요청이 검색 근거 청크 11번만 보냈는데 응답이 목록 밖의 99번을 인용한다.
        responseBody = """
                {"riskScore":50,"modelVersion":"m","promptVersion":"p",
                 "findings":[{"statement":"s","severity":"LOW","affectedPersonaCodes":["SENIOR"],
                 "retrievedContextChunkIds":[99],
                 "recommendation":null}]}""";

        assertInvalidProviderResponse();
    }

    @Test
    @DisplayName("null 검색 근거 청크 ID를 인용하면 계약 위반으로 끊는다")
    void nullContextChunkReferenceIsRejected() {
        responseBody = """
                {"riskScore":50,"modelVersion":"m","promptVersion":"p",
                 "findings":[{"statement":"s","severity":"LOW","affectedPersonaCodes":["SENIOR"],
                 "retrievedContextChunkIds":[null],"recommendation":null}]}""";

        assertInvalidProviderResponse();
    }

    @Test
    @DisplayName("같은 검색 근거 청크를 중복 인용하면 계약 위반으로 끊는다")
    void duplicateContextChunkReferenceIsRejected() {
        responseBody = """
                {"riskScore":50,"modelVersion":"m","promptVersion":"p",
                 "findings":[{"statement":"s","severity":"LOW","affectedPersonaCodes":["SENIOR"],
                 "retrievedContextChunkIds":[11,11],"recommendation":null}]}""";

        assertInvalidProviderResponse();
    }

    @Test
    @DisplayName("요청에 포함되지 않은 공식 사실을 인용하면 계약 위반으로 끊는다")
    void unknownFactReferenceIsRejected() {
        responseBody = """
                {"riskScore":50,"modelVersion":"m","promptVersion":"p",
                 "findings":[{"statement":"s","severity":"LOW","affectedPersonaCodes":["SENIOR"],
                 "retrievedContextChunkIds":[],"knownFactIds":[99],"recommendation":null}]}""";

        assertInvalidProviderResponse();
    }

    @Test
    @DisplayName("null 공식 사실 ID를 인용하면 계약 위반으로 끊는다")
    void nullFactReferenceIsRejected() {
        responseBody = """
                {"riskScore":50,"modelVersion":"m","promptVersion":"p",
                 "findings":[{"statement":"s","severity":"LOW","affectedPersonaCodes":["SENIOR"],
                 "retrievedContextChunkIds":[],"knownFactIds":[null],"recommendation":null}]}""";

        assertInvalidProviderResponse();
    }

    @Test
    @DisplayName("같은 공식 사실을 중복 인용하면 계약 위반으로 끊는다")
    void duplicateFactReferenceIsRejected() {
        responseBody = """
                {"riskScore":50,"modelVersion":"m","promptVersion":"p",
                 "findings":[{"statement":"s","severity":"LOW","affectedPersonaCodes":["SENIOR"],
                 "retrievedContextChunkIds":[],"knownFactIds":[7,7],"recommendation":null}]}""";

        assertInvalidProviderResponse();
    }

    private void assertInvalidProviderResponse() {
        assertThatThrownBy(() -> provider().analyze(request()))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROVIDER_RESPONSE_INVALID);
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    @DisplayName("응답을 계약대로 읽지 못하면 계약 위반으로 끊는다")
    void undeserializableResponseIsRejected() {
        responseBody = "{\"riskScore\": ";

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
