package com.crosschecklab.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

// ai-service HTTP 어댑터 검증. 실제 ai-service 대신 JDK 내장 HTTP 서버로 응답을 흉내낸다.
// 검증 대상은 요청 직렬화, 응답 역직렬화, 그리고 오류 → errorCode/retryable 매핑이다.
class HttpRiskAnalysisProviderTest {

    private static final String SUCCESS_BODY = """
            {"riskScore":82,"modelVersion":"mock-risk-v1","promptVersion":"mock-prompt-v1",
             "findings":[{"statement":"안정성 표현이 원금보장으로 오인될 가능성이 있습니다.","severity":"HIGH",
             "policyRuleCode":"STABILITY_KEYWORD",
             "affectedPersonaCodes":["FINANCIAL_BEGINNER"],
             "retrievedContextChunkIds":[11],
             "evidenceSpans":[{"chunkId":11,"excerpt":"원금손실 가능성"}],
             "knownFactIds":[7],
             "docClaim":{"excerpt":"확정된 상품 설명 텍스트"},
             "recommendation":"원금 손실 가능성을 명시하세요."}]}""";

    private HttpServer server;
    private final AtomicReference<String> capturedRequest = new AtomicReference<>();
    private final AtomicReference<String> capturedAccept = new AtomicReference<>();
    private int status = 200;
    private String responseBody = SUCCESS_BODY;
    private String responseContentType = "application/json";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/v1/risk-analyses", exchange -> {
            capturedRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            capturedAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", responseContentType);
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
                        "SCENARIO", allowInsecureHttp, "crosschecklab-local-internal-token"),
                new ObjectMapper());
    }

    private AnalysisRequest request() {
        return request("확정된 상품 설명 텍스트");
    }

    private AnalysisRequest request(String confirmedText) {
        return new AnalysisRequest(1L, "GUARANTEE_MISUNDERSTANDING_HIGH", confirmedText,
                List.of(PersonaCode.FINANCIAL_BEGINNER), "CORE_FINANCIAL_RISK_V1",
                List.of(RedTeamRuleCode.STABILITY_KEYWORD),
                List.of(1L),
                List.of(new AnalysisRequest.RetrievedContextPayload(
                        11L, 1L, EvidenceSourceType.INTERNAL_POLICY, "내부준칙",
                        "원금손실 가능성은 인접 표시", 1, 0.91),
                        new AnalysisRequest.RetrievedContextPayload(
                                12L, 1L, EvidenceSourceType.INTERNAL_POLICY, "내부준칙",
                                "중도해지 비용을 함께 표시", 2, 0.87)),
                List.of(new AnalysisRequest.KnownFactPayload(7L, "확정된 공식 사실")));
    }

    private AnalysisResult analyzeWithMockServer(String body) {
        return analyzeWithMockServer(body, request());
    }

    private AnalysisResult analyzeWithMockServer(String body, AnalysisRequest analysisRequest) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost");
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        mockServer.expect(requestTo("http://localhost/internal/v1/risk-analyses"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        HttpRiskAnalysisProvider provider = provider();
        try {
            Field restClient = HttpRiskAnalysisProvider.class.getDeclaredField("restClient");
            restClient.setAccessible(true);
            restClient.set(provider, builder.build());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("테스트용 RestClient 주입 실패", e);
        }
        try {
            return provider.analyze(analysisRequest);
        } finally {
            mockServer.verify();
        }
    }

    private static String validFindingJson() {
        return """
                {"statement":"진단","severity":"LOW","policyRuleCode":"STABILITY_KEYWORD",
                 "affectedPersonaCodes":["FINANCIAL_BEGINNER"],
                 "retrievedContextChunkIds":[11],
                 "evidenceSpans":[{"chunkId":11,"excerpt":"원금손실 가능성"}],
                 "knownFactIds":[],
                 "docClaim":{"excerpt":"확정된 상품 설명 텍스트"},
                 "recommendation":null}""";
    }

    private static String resultJson(String riskScore, List<String> findings) {
        return """
                {"riskScore":%s,"modelVersion":"m","promptVersion":"p","findings":[%s]}"""
                .formatted(riskScore, String.join(",", findings));
    }

    @Test
    @DisplayName("application/json 정상 응답을 AnalysisResult 로 읽고, 요청은 ai-service 계약 필드로 직렬화된다")
    void applicationJsonResponseIsParsed() {
        AnalysisResult result = provider().analyze(request());

        assertThat(result.riskScore()).isEqualTo(82);
        assertThat(result.modelVersion()).isEqualTo("mock-risk-v1");
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(Severity.HIGH);
            assertThat(finding.policyRuleCode()).isEqualTo(RedTeamRuleCode.STABILITY_KEYWORD);
            assertThat(finding.affectedPersonaCodes()).containsExactly(PersonaCode.FINANCIAL_BEGINNER);
            assertThat(finding.retrievedContextChunkIds()).containsExactly(11L);
            assertThat(finding.evidenceSpans()).singleElement().satisfies(span -> {
                assertThat(span.chunkId()).isEqualTo(11L);
                assertThat(span.excerpt()).isEqualTo("원금손실 가능성");
            });
            assertThat(finding.knownFactIds()).containsExactly(7L);
            assertThat(finding.docClaim().excerpt()).isEqualTo("확정된 상품 설명 텍스트");
        });
        // ai-service 는 extra="forbid" 라 필드명이 정확히 일치해야 한다.
        assertThat(capturedRequest.get())
                .contains("\"analysisId\":1", "\"scenarioCode\"", "\"confirmedText\"", "\"personaCodes\"",
                        "\"redTeamPackCode\"", "\"ruleCodes\"", "\"selectedEvidenceDocumentIds\":[1]",
                        "\"retrievedContexts\"", "\"chunkId\":11", "\"evidenceDocumentId\":1",
                        "\"sourceType\"", "\"chunkText\":\"원금손실 가능성은 인접 표시\"");
        assertThat(capturedRequest.get()).contains("\"knownFacts\"", "\"factId\":7");
        assertThat(capturedRequest.get()).doesNotContain("\"evidenceDocuments\"", "\"content\"");
        assertThat(capturedAccept.get()).isEqualTo("application/json");
    }

    @Test
    @DisplayName("application/octet-stream 원시 응답 본문의 JSON도 AnalysisResult 로 읽는다")
    void rawOctetStreamJsonResponseIsParsed() {
        responseContentType = "application/octet-stream";

        AnalysisResult result = provider().analyze(request());

        assertThat(result.riskScore()).isEqualTo(82);
        assertThat(result.modelVersion()).isEqualTo("mock-risk-v1");
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.retrievedContextChunkIds()).containsExactly(11L);
            assertThat(finding.evidenceSpans()).singleElement().satisfies(span ->
                    assertThat(span.excerpt()).isEqualTo("원금손실 가능성"));
        });
        assertThat(capturedAccept.get()).isEqualTo("application/json");
    }

    @Test
    @DisplayName("findings 필드가 null이거나 없으면 빠른 계약 검증에서 거부한다")
    void requiredFindingsAreRejectedWhenNullOrAbsent() {
        List<String> invalidBodies = List.of(
                """
                        {"riskScore":null,"modelVersion":"m","promptVersion":"p","findings":null}""",
                """
                        {"riskScore":null,"modelVersion":"m","promptVersion":"p"}""");

        for (String invalidBody : invalidBodies) {
            assertThatThrownBy(() -> analyzeWithMockServer(invalidBody))
                    .isInstanceOfSatisfying(ProviderException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROVIDER_RESPONSE_INVALID));
        }
    }

    @Test
    @DisplayName("finding 없는 clean 응답은 null 점수만 허용한다")
    void cleanResponseRequiresNullScore() {
        AnalysisResult clean = analyzeWithMockServer(resultJson("null", List.of()));

        assertThat(clean.riskScore()).isNull();
        assertThat(clean.findings()).isEmpty();
        assertThatThrownBy(() -> analyzeWithMockServer(resultJson("0", List.of())))
                .isInstanceOfSatisfying(ProviderException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROVIDER_RESPONSE_INVALID));
    }

    @Test
    @DisplayName("유효한 finding 2개와 최대 20개를 허용하고 21개는 거부한다")
    void findingCountBoundaryIsEnforced() {
        List<String> twoFindings = IntStream.range(0, 2)
                .mapToObj(index -> validFindingJson())
                .toList();
        List<String> twentyFindings = IntStream.range(0, AnalysisResult.MAX_FINDINGS)
                .mapToObj(index -> validFindingJson())
                .toList();
        List<String> twentyOneFindings = IntStream.rangeClosed(0, AnalysisResult.MAX_FINDINGS)
                .mapToObj(index -> validFindingJson())
                .toList();

        assertThat(analyzeWithMockServer(resultJson("40", twoFindings)).findings()).hasSize(2);
        assertThat(analyzeWithMockServer(resultJson("40", twentyFindings)).findings())
                .hasSize(AnalysisResult.MAX_FINDINGS);
        assertThatThrownBy(() -> analyzeWithMockServer(resultJson("40", twentyOneFindings)))
                .isInstanceOfSatisfying(ProviderException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROVIDER_RESPONSE_INVALID));
    }

    @Test
    @DisplayName("두 번째 finding이 잘못되면 응답 전체를 거부한다")
    void invalidSecondFindingRejectsWholeResponse() {
        String invalidSecond = validFindingJson().replace("\"statement\":\"진단\"", "\"statement\":\" \"");

        assertThatThrownBy(() -> analyzeWithMockServer(
                resultJson("40", List.of(validFindingJson(), invalidSecond))))
                .isInstanceOfSatisfying(ProviderException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROVIDER_RESPONSE_INVALID));
    }

    @Test
    @DisplayName("finding이 있는 진단 응답도 riskScore null을 보존한다")
    void nonEmptyDiagnosticMayHaveNullScore() {
        AnalysisResult result = analyzeWithMockServer(resultJson("null", List.of(validFindingJson())));

        assertThat(result.riskScore()).isNull();
        assertThat(result.findings()).hasSize(1);
    }

    @Test
    @DisplayName("docClaim 또는 excerpt가 누락·null·공백이면 계약 위반으로 끊는다")
    void requiredDocClaimIsRejectedWhenMissingNullOrBlank() {
        List<String> invalidFindings = List.of(
                validFindingJson().replace(
                        "\"docClaim\":{\"excerpt\":\"확정된 상품 설명 텍스트\"},", ""),
                validFindingJson().replace(
                        "\"docClaim\":{\"excerpt\":\"확정된 상품 설명 텍스트\"}", "\"docClaim\":null"),
                validFindingJson().replace(
                        "\"docClaim\":{\"excerpt\":\"확정된 상품 설명 텍스트\"}", "\"docClaim\":{}"),
                validFindingJson().replace(
                        "\"docClaim\":{\"excerpt\":\"확정된 상품 설명 텍스트\"}",
                        "\"docClaim\":{\"excerpt\":null}"),
                validFindingJson().replace(
                        "\"docClaim\":{\"excerpt\":\"확정된 상품 설명 텍스트\"}",
                        "\"docClaim\":{\"excerpt\":\"   \"}"));

        for (String invalidFinding : invalidFindings) {
            assertThatThrownBy(() -> analyzeWithMockServer(resultJson("40", List.of(invalidFinding))))
                    .isInstanceOfSatisfying(ProviderException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROVIDER_RESPONSE_INVALID));
        }
    }

    @Test
    @DisplayName("docClaim excerpt는 Unicode code point 400개를 허용하고 UTF-16 길이는 기준으로 삼지 않는다")
    void docClaimAllowsFourHundredUnicodeCodePoints() {
        String excerpt = "😀".repeat(400);
        String finding = validFindingJson().replace("확정된 상품 설명 텍스트", excerpt);

        AnalysisResult result = analyzeWithMockServer(
                resultJson("40", List.of(finding)), request(excerpt));

        assertThat(excerpt.length()).isGreaterThan(400);
        assertThat(result.findings()).singleElement().satisfies(found ->
                assertThat(found.docClaim().excerpt()).isEqualTo(excerpt));
    }

    @Test
    @DisplayName("docClaim excerpt가 Unicode code point 400개를 넘으면 계약 위반으로 끊는다")
    void docClaimOverFourHundredUnicodeCodePointsIsRejected() {
        String excerpt = "가".repeat(401);
        String finding = validFindingJson().replace("확정된 상품 설명 텍스트", excerpt);

        assertThatThrownBy(() -> analyzeWithMockServer(
                resultJson("40", List.of(finding)), request(excerpt)))
                .isInstanceOfSatisfying(ProviderException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROVIDER_RESPONSE_INVALID));
    }

    @Test
    @DisplayName("confirmedText에 정확히 존재하지 않는 docClaim excerpt는 계약 위반으로 끊는다")
    void docClaimOutsideConfirmedTextIsRejected() {
        String finding = validFindingJson().replace(
                "확정된 상품 설명 텍스트", "확정 원문에 없는 주장");

        assertThatThrownBy(() -> analyzeWithMockServer(resultJson("40", List.of(finding))))
                .isInstanceOfSatisfying(ProviderException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROVIDER_RESPONSE_INVALID));
    }

    @Test
    @DisplayName("confirmedText에서 겹쳐 두 번 나타나는 docClaim excerpt는 모호하므로 거부한다")
    void overlappingDuplicateDocClaimIsRejected() {
        String finding = validFindingJson().replace("확정된 상품 설명 텍스트", "가가");

        assertThatThrownBy(() -> analyzeWithMockServer(
                resultJson("40", List.of(finding)), request("가가가")))
                .isInstanceOfSatisfying(ProviderException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROVIDER_RESPONSE_INVALID));
    }

    @Test
    @DisplayName("docClaim의 앞뒤 공백도 confirmedText에 정확히 한 번 존재하면 허용한다")
    void docClaimWithExactLeadingAndTrailingWhitespaceIsAllowed() {
        String excerpt = "  확정된 상품 설명 텍스트  ";
        String finding = validFindingJson().replace("확정된 상품 설명 텍스트", excerpt);

        AnalysisResult result = analyzeWithMockServer(
                resultJson("40", List.of(finding)), request("앞" + excerpt + "뒤"));

        assertThat(result.findings()).singleElement().satisfies(found ->
                assertThat(found.docClaim().excerpt()).isEqualTo(excerpt));
    }

    @Test
    @DisplayName("knownFactIds가 비어 있지 않아도 필수 docClaim을 대신하지 못한다")
    void knownFactDoesNotReplaceRequiredDocClaim() {
        String finding = validFindingJson()
                .replace("\"knownFactIds\":[]", "\"knownFactIds\":[7]")
                .replace(
                        "\"docClaim\":{\"excerpt\":\"확정된 상품 설명 텍스트\"},", "");

        assertThatThrownBy(() -> analyzeWithMockServer(resultJson("40", List.of(finding))))
                .isInstanceOfSatisfying(ProviderException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROVIDER_RESPONSE_INVALID));
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
    @DisplayName("500은 provider 응답 본문과 무관하게 AI_SERVICE_TEMPORARY_FAILURE, 재시도 가능")
    void serverFailureIsRetryable() {
        status = 500;
        responseBody = """
                {"errorCode":"PROVIDER_RESPONSE_INVALID","message":"invalid","retryable":false}""";

        assertThatThrownBy(() -> provider().analyze(request()))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_SERVICE_TEMPORARY_FAILURE);
                    assertThat(e.isRetryable()).isTrue();
                });
    }

    @Test
    @DisplayName("HIGH Finding 에 근거가 없으면 계약 위반으로 끊는다 (재시도 불가)")
    void highFindingWithoutEvidence() {
        responseBody = """
                {"riskScore":82,"modelVersion":"m","promptVersion":"p",
                 "findings":[{"statement":"s","severity":"HIGH","policyRuleCode":"STABILITY_KEYWORD",
                 "affectedPersonaCodes":["FINANCIAL_BEGINNER"],
                 "retrievedContextChunkIds":[],
                 "docClaim":{"excerpt":"확정된 상품 설명 텍스트"},
                 "recommendation":null}]}""";

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
                 "findings":[{"statement":"s","severity":"LOW","policyRuleCode":"STABILITY_KEYWORD",
                 "affectedPersonaCodes":["FINANCIAL_BEGINNER"],
                 "retrievedContextChunkIds":[99],
                 "docClaim":{"excerpt":"확정된 상품 설명 텍스트"},
                 "recommendation":null}]}""";

        assertInvalidProviderResponse();
    }

    @Test
    @DisplayName("null 검색 근거 청크 ID를 인용하면 계약 위반으로 끊는다")
    void nullContextChunkReferenceIsRejected() {
        responseBody = """
                {"riskScore":50,"modelVersion":"m","promptVersion":"p",
                 "findings":[{"statement":"s","severity":"LOW","policyRuleCode":"STABILITY_KEYWORD",
                 "affectedPersonaCodes":["FINANCIAL_BEGINNER"],
                 "retrievedContextChunkIds":[null],
                 "docClaim":{"excerpt":"확정된 상품 설명 텍스트"},
                 "recommendation":null}]}""";

        assertInvalidProviderResponse();
    }

    @Test
    @DisplayName("같은 검색 근거 청크를 중복 인용하면 계약 위반으로 끊는다")
    void duplicateContextChunkReferenceIsRejected() {
        responseBody = """
                {"riskScore":50,"modelVersion":"m","promptVersion":"p",
                 "findings":[{"statement":"s","severity":"LOW","policyRuleCode":"STABILITY_KEYWORD",
                 "affectedPersonaCodes":["FINANCIAL_BEGINNER"],
                 "retrievedContextChunkIds":[11,11],
                 "docClaim":{"excerpt":"확정된 상품 설명 텍스트"},
                 "recommendation":null}]}""";

        assertInvalidProviderResponse();
    }

    @Test
    @DisplayName("유효한 청크 ID만 인용해도 정확한 근거 범위가 없으면 계약 위반으로 끊는다")
    void citationWithoutEvidenceSpanIsRejected() {
        responseBody = """
                {"riskScore":50,"modelVersion":"m","promptVersion":"p",
                 "findings":[{"statement":"s","severity":"LOW","policyRuleCode":"STABILITY_KEYWORD",
                 "affectedPersonaCodes":["FINANCIAL_BEGINNER"],
                 "retrievedContextChunkIds":[11],"evidenceSpans":[],
                 "docClaim":{"excerpt":"확정된 상품 설명 텍스트"},
                 "recommendation":null}]}""";

        assertInvalidProviderResponse();
    }

    @Test
    @DisplayName("인용한 각 청크에 정확한 근거 범위가 없으면 계약 위반으로 끊는다")
    void citedChunkWithoutMatchingEvidenceSpanIsRejected() {
        responseBody = """
                {"riskScore":50,"modelVersion":"m","promptVersion":"p",
                 "findings":[{"statement":"s","severity":"LOW","policyRuleCode":"STABILITY_KEYWORD",
                 "affectedPersonaCodes":["FINANCIAL_BEGINNER"],
                 "retrievedContextChunkIds":[11,12],
                 "evidenceSpans":[{"chunkId":11,"excerpt":"원금손실 가능성"}],
                 "docClaim":{"excerpt":"확정된 상품 설명 텍스트"},
                 "recommendation":null}]}""";

        assertInvalidProviderResponse();
    }

    @Test
    @DisplayName("공백뿐인 excerpt는 계약 위반으로 끊는다")
    void blankEvidenceSpanIsRejected() {
        responseBody = """
                {"riskScore":50,"modelVersion":"m","promptVersion":"p",
                 "findings":[{"statement":"s","severity":"LOW","policyRuleCode":"STABILITY_KEYWORD",
                 "affectedPersonaCodes":["FINANCIAL_BEGINNER"],
                 "retrievedContextChunkIds":[11],
                 "evidenceSpans":[{"chunkId":11,"excerpt":"   "}],
                 "docClaim":{"excerpt":"확정된 상품 설명 텍스트"},
                 "recommendation":null}]}""";

        assertInvalidProviderResponse();
    }

    @Test
    @DisplayName("검색된 청크 원문에 정확히 포함되지 않은 excerpt는 계약 위반으로 끊는다")
    void inexactEvidenceSpanIsRejected() {
        responseBody = """
                {"riskScore":50,"modelVersion":"m","promptVersion":"p",
                 "findings":[{"statement":"s","severity":"LOW","policyRuleCode":"STABILITY_KEYWORD",
                 "affectedPersonaCodes":["FINANCIAL_BEGINNER"],
                 "retrievedContextChunkIds":[11],
                 "evidenceSpans":[{"chunkId":11,"excerpt":"원금 손실 가능성"}],
                 "docClaim":{"excerpt":"확정된 상품 설명 텍스트"},
                 "recommendation":null}]}""";

        assertInvalidProviderResponse();
    }

    @Test
    @DisplayName("근거 범위는 인용한 청크만 참조해야 한다")
    void evidenceSpanForUncitedChunkIsRejected() {
        responseBody = """
                {"riskScore":50,"modelVersion":"m","promptVersion":"p",
                 "findings":[{"statement":"s","severity":"LOW","policyRuleCode":"STABILITY_KEYWORD",
                 "affectedPersonaCodes":["FINANCIAL_BEGINNER"],
                 "retrievedContextChunkIds":[11],
                 "evidenceSpans":[{"chunkId":12,"excerpt":"중도해지 비용"}],
                 "docClaim":{"excerpt":"확정된 상품 설명 텍스트"},
                 "recommendation":null}]}""";

        assertInvalidProviderResponse();
    }

    @Test
    @DisplayName("같은 근거 범위를 중복 반환하면 계약 위반으로 끊는다")
    void duplicateEvidenceSpanIsRejected() {
        responseBody = """
                {"riskScore":50,"modelVersion":"m","promptVersion":"p",
                 "findings":[{"statement":"s","severity":"LOW","policyRuleCode":"STABILITY_KEYWORD",
                 "affectedPersonaCodes":["FINANCIAL_BEGINNER"],
                 "retrievedContextChunkIds":[11],
                 "evidenceSpans":[
                   {"chunkId":11,"excerpt":"원금손실 가능성"},
                   {"chunkId":11,"excerpt":"원금손실 가능성"}],
                 "docClaim":{"excerpt":"확정된 상품 설명 텍스트"},
                 "recommendation":null}]}""";

        assertInvalidProviderResponse();
    }

    @Test
    @DisplayName("요청에 포함되지 않은 공식 사실을 인용하면 계약 위반으로 끊는다")
    void unknownFactReferenceIsRejected() {
        responseBody = """
                {"riskScore":50,"modelVersion":"m","promptVersion":"p",
                 "findings":[{"statement":"s","severity":"LOW","policyRuleCode":"STABILITY_KEYWORD",
                 "affectedPersonaCodes":["FINANCIAL_BEGINNER"],
                 "retrievedContextChunkIds":[11],
                 "evidenceSpans":[{"chunkId":11,"excerpt":"원금손실 가능성"}],
                 "knownFactIds":[99],
                 "docClaim":{"excerpt":"확정된 상품 설명 텍스트"},
                 "recommendation":null}]}""";

        assertInvalidProviderResponse();
    }

    @Test
    @DisplayName("null 공식 사실 ID를 인용하면 계약 위반으로 끊는다")
    void nullFactReferenceIsRejected() {
        responseBody = """
                {"riskScore":50,"modelVersion":"m","promptVersion":"p",
                 "findings":[{"statement":"s","severity":"LOW","policyRuleCode":"STABILITY_KEYWORD",
                 "affectedPersonaCodes":["FINANCIAL_BEGINNER"],
                 "retrievedContextChunkIds":[11],
                 "evidenceSpans":[{"chunkId":11,"excerpt":"원금손실 가능성"}],
                 "knownFactIds":[null],
                 "docClaim":{"excerpt":"확정된 상품 설명 텍스트"},
                 "recommendation":null}]}""";

        assertInvalidProviderResponse();
    }

    @Test
    @DisplayName("같은 공식 사실을 중복 인용하면 계약 위반으로 끊는다")
    void duplicateFactReferenceIsRejected() {
        responseBody = """
                {"riskScore":50,"modelVersion":"m","promptVersion":"p",
                 "findings":[{"statement":"s","severity":"LOW","policyRuleCode":"STABILITY_KEYWORD",
                 "affectedPersonaCodes":["FINANCIAL_BEGINNER"],
                 "retrievedContextChunkIds":[11],
                 "evidenceSpans":[{"chunkId":11,"excerpt":"원금손실 가능성"}],
                 "knownFactIds":[7,7],
                 "docClaim":{"excerpt":"확정된 상품 설명 텍스트"},
                 "recommendation":null}]}""";

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
    @DisplayName("application/octet-stream 응답의 본문이 JSON이 아니면 계약 위반으로 끊는다")
    void invalidOctetStreamResponseIsRejected() {
        responseContentType = "application/octet-stream";
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
