package com.crosschecklab.analysis.provider.http;

import com.crosschecklab.analysis.provider.ProviderException;
import com.crosschecklab.analysis.provider.RiskAnalysisProvider;
import com.crosschecklab.analysis.provider.dto.AnalysisRequest;
import com.crosschecklab.analysis.provider.dto.AnalysisResult;
import com.crosschecklab.analysis.provider.dto.FindingPayload;
import com.crosschecklab.global.common.enums.PersonaCode;
import com.crosschecklab.global.common.enums.RedTeamRuleCode;
import com.crosschecklab.global.common.enums.Severity;
import com.crosschecklab.global.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

// ai-service(FastAPI Mock) HTTP 어댑터.
// ai-service 는 동기 응답이라 이 호출 하나가 곧 분석 실행이다. 비동기 처리는 호출자(@Async)가 담당한다.
@Slf4j
@Component
public class HttpRiskAnalysisProvider implements RiskAnalysisProvider {

    private static final String ANALYZE_PATH = "/internal/v1/risk-analyses";
    private static final int MAX_VERSION_LENGTH = 50;
    private static final int MAX_STATEMENT_LENGTH = 1_000;
    private static final int MAX_RECOMMENDATION_LENGTH = 1_000;
    private static final int MAX_PERSONA_CODES = 12;
    private static final int MAX_RETRIEVED_CONTEXT_CHUNK_IDS = 20;
    private static final int MAX_KNOWN_FACT_IDS = 50;
    private static final int MAX_EVIDENCE_SPANS = 60;
    private static final int MAX_EVIDENCE_EXCERPT_LENGTH = 8_000;
    private static final int MAX_DOC_CLAIM_CODE_POINTS = 400;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpRiskAnalysisProvider(AiServiceProperties properties, ObjectMapper objectMapper) {
        requireSecureTransport(properties.baseUrl(), properties.allowInsecureHttp());
        if (properties.internalToken() == null || properties.internalToken().isBlank()) {
            throw new IllegalStateException("ai-service.internal-token 은 비어 있을 수 없습니다");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.internalToken())
                .requestFactory(requestFactory)
                .build();
        this.objectMapper = objectMapper;
    }

    // 요청 본문에 확정 텍스트와 검색된 근거 청크가 실리므로 평문 전송은 로컬 개발에서만 허용한다.
    private static void requireSecureTransport(String baseUrl, boolean allowInsecureHttp) {
        URI uri = URI.create(baseUrl);
        String host = uri.getHost();
        if (allowInsecureHttp || "https".equalsIgnoreCase(uri.getScheme())
                || "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host)) {
            return;
        }
        throw new IllegalStateException(
                "ai-service.base-url 은 https 여야 합니다 (http 는 localhost 만 허용): " + baseUrl);
    }

    @Override
    public AnalysisResult analyze(AnalysisRequest request) {
        try {
            byte[] responseBody = restClient.post()
                    .uri(ANALYZE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((req, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw toProviderException(response);
                        }
                        return response.getBody().readAllBytes();
                    });
            AnalysisResult result = parseResponse(responseBody);
            return validate(request, result);
        } catch (ResourceAccessException e) {
            // 연결 실패 / 읽기 타임아웃 — 같은 요청을 그대로 재시도할 수 있다.
            throw new ProviderException(ErrorCode.AI_SERVICE_TEMPORARY_FAILURE, true,
                    "ai-service 연결 실패: " + e.getMessage());
        } catch (RestClientException e) {
            // 응답 본문을 계약대로 읽지 못한 경우(역직렬화 실패 등). 재시도해도 같은 결과다.
            throw new ProviderException(ErrorCode.PROVIDER_RESPONSE_INVALID, false,
                    "ai-service 응답 해석 실패: " + e.getMessage());
        }
    }

    private AnalysisResult parseResponse(byte[] responseBody) {
        if (responseBody == null || responseBody.length == 0) {
            throw invalid("응답 본문이 비어 있음");
        }
        try {
            return objectMapper.readValue(responseBody, AnalysisResult.class);
        } catch (IOException e) {
            throw invalid("ai-service 응답 JSON 해석 실패: " + e.getMessage());
        }
    }

    private ProviderException toProviderException(ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        AiErrorResponse error = readErrorBody(response);
        // HTTP 상태가 재시도 정책의 기준이다. 4xx 계약/설정 오류는 429만 예외로 재시도한다.
        boolean retryable = status.value() == 429 || status.is5xxServerError();
        String detail = error != null ? error.errorCode() + " / " + error.message() : "본문 없음";
        log.warn("ai-service 오류 응답 status={} {}", status.value(), detail);
        return new ProviderException(
                retryable ? ErrorCode.AI_SERVICE_TEMPORARY_FAILURE : ErrorCode.PROVIDER_RESPONSE_INVALID,
                retryable, detail);
    }

    private AiErrorResponse readErrorBody(ClientHttpResponse response) {
        try {
            return objectMapper.readValue(response.getBody(), AiErrorResponse.class);
        } catch (Exception e) {
            return null;
        }
    }

    // 계약 위반은 재시도해도 같은 결과이므로 retryable=false 로 끊는다.
    private AnalysisResult validate(AnalysisRequest request, AnalysisResult result) {
        if (result == null) {
            throw invalid("응답이 비어 있음");
        }
        if (result.findings() == null) {
            throw invalid("findings 가 없음");
        }
        if (result.findings().size() > AnalysisResult.MAX_FINDINGS) {
            throw invalid("findings 개수 초과: " + result.findings().size());
        }
        if (result.findings().isEmpty() && result.riskScore() != null) {
            throw invalid("findings 가 비어 있으면 riskScore 는 null 이어야 함");
        }
        if (result.riskScore() != null && (result.riskScore() < 0 || result.riskScore() > 100)) {
            throw invalid("riskScore 범위 초과: " + result.riskScore());
        }
        requireNonBlank(result.modelVersion(), "modelVersion", MAX_VERSION_LENGTH);
        requireNonBlank(result.promptVersion(), "promptVersion", MAX_VERSION_LENGTH);

        List<AnalysisRequest.RetrievedContextPayload> contexts = request.retrievedContexts() == null
                ? List.of() : request.retrievedContexts();
        Map<Long, AnalysisRequest.RetrievedContextPayload> contextsByChunkId = new HashMap<>();
        for (AnalysisRequest.RetrievedContextPayload context : contexts) {
            if (context != null && context.chunkId() != null) {
                contextsByChunkId.put(context.chunkId(), context);
            }
        }
        Set<Long> knownFacts = request.knownFacts() == null ? Set.of() : request.knownFacts().stream()
                .map(AnalysisRequest.KnownFactPayload::factId).collect(Collectors.toSet());
        Set<RedTeamRuleCode> selectedRules = request.ruleCodes() == null
                ? Set.of() : new HashSet<>(request.ruleCodes());
        Set<PersonaCode> selectedPersonas = request.personaCodes() == null
                ? Set.of() : new HashSet<>(request.personaCodes());

        for (FindingPayload finding : result.findings()) {
            if (finding == null || finding.severity() == null) {
                throw invalid("finding 또는 severity 가 비어 있음");
            }
            requireNonBlank(finding.statement(), "finding.statement", MAX_STATEMENT_LENGTH);
            if (finding.policyRuleCode() == null || !selectedRules.contains(finding.policyRuleCode())) {
                throw invalid("요청에서 선택되지 않은 policyRuleCode: " + finding.policyRuleCode());
            }
            if (finding.affectedPersonaCodes() == null || finding.affectedPersonaCodes().isEmpty()) {
                throw invalid("finding 에 affectedPersonaCodes 가 없음");
            }
            if (finding.affectedPersonaCodes().size() > MAX_PERSONA_CODES) {
                throw invalid("affectedPersonaCodes 개수 초과: " + finding.affectedPersonaCodes().size());
            }
            Set<PersonaCode> uniquePersonaCodes = new HashSet<>();
            for (PersonaCode personaCode : finding.affectedPersonaCodes()) {
                if (personaCode == null) {
                    throw invalid("affectedPersonaCodes 에 null 이 있음");
                }
                if (!uniquePersonaCodes.add(personaCode)) {
                    throw invalid("중복된 affectedPersonaCode: " + personaCode);
                }
                if (!selectedPersonas.contains(personaCode)) {
                    throw invalid("요청에서 선택되지 않은 affectedPersonaCode: " + personaCode);
                }
            }
            if (finding.retrievedContextChunkIds() == null || finding.retrievedContextChunkIds().isEmpty()) {
                throw invalid("finding 에 retrievedContextChunkIds 가 없음");
            }
            List<Long> citedChunkIds = finding.retrievedContextChunkIds();
            if (citedChunkIds.size() > MAX_RETRIEVED_CONTEXT_CHUNK_IDS) {
                throw invalid("retrievedContextChunkIds 개수 초과: " + citedChunkIds.size());
            }
            if (finding.severity() == Severity.HIGH && citedChunkIds.isEmpty()) {
                throw invalid("HIGH Finding 에 근거 인용이 없음");
            }
            Set<Long> uniqueCitedChunkIds = new HashSet<>();
            for (Long chunkId : citedChunkIds) {
                if (chunkId == null) {
                    throw invalid("근거 인용에 retrievedContextChunkId 가 없음");
                }
                if (!uniqueCitedChunkIds.add(chunkId)) {
                    throw invalid("중복된 검색 근거 청크 인용: " + chunkId);
                }
                if (!contextsByChunkId.containsKey(chunkId)) {
                    throw invalid("요청에서 검색되지 않은 근거 청크 인용: " + chunkId);
                }
            }
            if (finding.evidenceSpans() == null || finding.evidenceSpans().isEmpty()) {
                throw invalid("finding 에 evidenceSpans 가 없음");
            }
            if (finding.evidenceSpans().size() > MAX_EVIDENCE_SPANS) {
                throw invalid("evidenceSpans 개수 초과: " + finding.evidenceSpans().size());
            }
            Set<String> uniqueSpans = new HashSet<>();
            Set<Long> spannedChunkIds = new HashSet<>();
            for (FindingPayload.EvidenceSpanPayload span : finding.evidenceSpans()) {
                if (span == null || span.chunkId() == null) {
                    throw invalid("근거 범위에 chunkId 가 없음");
                }
                if (span.excerpt() == null || span.excerpt().isBlank()) {
                    throw invalid("근거 범위의 excerpt 가 비어 있음");
                }
                if (span.excerpt().length() > MAX_EVIDENCE_EXCERPT_LENGTH) {
                    throw invalid("근거 범위의 excerpt 길이 초과: " + span.chunkId());
                }
                if (!uniqueCitedChunkIds.contains(span.chunkId())) {
                    throw invalid("인용되지 않은 청크의 근거 범위: " + span.chunkId());
                }
                AnalysisRequest.RetrievedContextPayload context = contextsByChunkId.get(span.chunkId());
                if (context == null || context.chunkText() == null || !context.chunkText().contains(span.excerpt())) {
                    throw invalid("검색 근거 청크에 정확히 포함되지 않은 excerpt: " + span.chunkId());
                }
                String spanIdentity = span.chunkId() + "\u0000" + span.excerpt();
                if (!uniqueSpans.add(spanIdentity)) {
                    throw invalid("중복된 근거 범위: " + span.chunkId());
                }
                spannedChunkIds.add(span.chunkId());
            }
            if (!spannedChunkIds.equals(uniqueCitedChunkIds)) {
                throw invalid("인용된 모든 검색 근거 청크에 evidenceSpan 이 필요함");
            }
            if (finding.knownFactIds() == null) {
                throw invalid("finding 에 knownFactIds 가 없음");
            }
            if (finding.knownFactIds().size() > MAX_KNOWN_FACT_IDS) {
                throw invalid("knownFactIds 개수 초과: " + finding.knownFactIds().size());
            }
            Set<Long> citedFacts = new HashSet<>();
            for (Long factId : finding.knownFactIds()) {
                if (factId == null) {
                    throw invalid("사실 인용에 factId 가 없음");
                }
                if (!citedFacts.add(factId)) {
                    throw invalid("중복된 사실 인용: " + factId);
                }
                if (!knownFacts.contains(factId)) {
                    throw invalid("요청에 없는 사실 인용: " + factId);
                }
            }
            validateDocClaim(request.confirmedText(), finding.docClaim());
            if (finding.recommendation() != null
                    && finding.recommendation().length() > MAX_RECOMMENDATION_LENGTH) {
                throw invalid("finding.recommendation 길이 초과");
            }
        }
        return result;
    }

    private void validateDocClaim(String confirmedText, FindingPayload.DocClaimPayload docClaim) {
        if (docClaim == null || docClaim.excerpt() == null || docClaim.excerpt().isBlank()) {
            throw invalid("finding 에 docClaim.excerpt 가 없음");
        }
        String excerpt = docClaim.excerpt();
        if (excerpt.codePointCount(0, excerpt.length()) > MAX_DOC_CLAIM_CODE_POINTS) {
            throw invalid("finding.docClaim.excerpt 길이 초과");
        }
        if (confirmedText == null) {
            throw invalid("요청의 confirmedText 가 없음");
        }
        int first = confirmedText.indexOf(excerpt);
        if (first < 0 || confirmedText.indexOf(excerpt, first + 1) >= 0) {
            throw invalid("confirmedText 에 exact docClaim excerpt 범위가 없거나 둘 이상임");
        }
    }

    private void requireNonBlank(String value, String fieldName, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw invalid(fieldName + " 이 비어 있음");
        }
        if (value.length() > maximumLength) {
            throw invalid(fieldName + " 길이 초과");
        }
    }

    private ProviderException invalid(String detail) {
        return new ProviderException(ErrorCode.PROVIDER_RESPONSE_INVALID, false, detail);
    }

    // ai-service 오류 응답 스키마
    private record AiErrorResponse(String errorCode, String message, boolean retryable) {
    }
}
