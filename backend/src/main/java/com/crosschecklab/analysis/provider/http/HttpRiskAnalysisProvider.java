package com.crosschecklab.analysis.provider.http;

import com.crosschecklab.analysis.provider.ProviderException;
import com.crosschecklab.analysis.provider.RiskAnalysisProvider;
import com.crosschecklab.analysis.provider.dto.AnalysisRequest;
import com.crosschecklab.analysis.provider.dto.AnalysisResult;
import com.crosschecklab.analysis.provider.dto.FindingPayload;
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
            AnalysisResult result = restClient.post()
                    .uri(ANALYZE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, response) -> {
                        throw toProviderException(response);
                    })
                    .body(AnalysisResult.class);
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
        if (result == null || result.findings() == null || result.findings().isEmpty()) {
            throw invalid("findings 가 비어 있음");
        }
        if (result.riskScore() < 0 || result.riskScore() > 100) {
            throw invalid("riskScore 범위 초과: " + result.riskScore());
        }
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

        for (FindingPayload finding : result.findings()) {
            if (finding == null || finding.severity() == null) {
                throw invalid("finding 또는 severity 가 비어 있음");
            }
            if (finding.retrievedContextChunkIds() == null) {
                throw invalid("finding 에 retrievedContextChunkIds 가 없음");
            }
            List<Long> citedChunkIds = finding.retrievedContextChunkIds();
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
            Set<String> uniqueSpans = new HashSet<>();
            Set<Long> spannedChunkIds = new HashSet<>();
            for (FindingPayload.EvidenceSpanPayload span : finding.evidenceSpans()) {
                if (span == null || span.chunkId() == null) {
                    throw invalid("근거 범위에 chunkId 가 없음");
                }
                if (span.excerpt() == null || span.excerpt().isBlank()) {
                    throw invalid("근거 범위의 excerpt 가 비어 있음");
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
        }
        return result;
    }

    private ProviderException invalid(String detail) {
        return new ProviderException(ErrorCode.PROVIDER_RESPONSE_INVALID, false, detail);
    }

    // ai-service 오류 응답 스키마
    private record AiErrorResponse(String errorCode, String message, boolean retryable) {
    }
}
