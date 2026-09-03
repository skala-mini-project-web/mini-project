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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
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
        requireSecureTransport(properties.baseUrl());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
        this.objectMapper = objectMapper;
    }

    // 요청 본문에 확정 텍스트와 근거 문서 원문이 실리므로 평문 전송은 로컬 개발에서만 허용한다.
    private static void requireSecureTransport(String baseUrl) {
        URI uri = URI.create(baseUrl);
        String host = uri.getHost();
        if ("https".equalsIgnoreCase(uri.getScheme())
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
        // 응답 본문의 retryable 을 우선 신뢰하고, 본문을 못 읽으면 5xx 만 재시도 가능으로 본다.
        boolean retryable = error != null ? error.retryable() : status.is5xxServerError();
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
        Set<Long> selected = request.evidenceDocuments().stream()
                .map(AnalysisRequest.EvidenceDocumentPayload::id).collect(Collectors.toSet());

        for (FindingPayload finding : result.findings()) {
            if (finding == null || finding.severity() == null) {
                throw invalid("finding 또는 severity 가 비어 있음");
            }
            List<FindingPayload.EvidenceRefPayload> references =
                    finding.evidenceReferences() == null ? List.of() : finding.evidenceReferences();
            if (finding.severity() == Severity.HIGH && references.isEmpty()) {
                throw invalid("HIGH Finding 에 근거 인용이 없음");
            }
            // 선택하지 않은 근거를 인용하면 결과 조회에서 그 문서의 제목·출처가 노출된다.
            for (FindingPayload.EvidenceRefPayload reference : references) {
                if (reference == null || reference.evidenceDocumentId() == null) {
                    throw invalid("근거 인용에 evidenceDocumentId 가 없음");
                }
                if (!selected.contains(reference.evidenceDocumentId())) {
                    throw invalid("선택하지 않은 근거 문서 인용: " + reference.evidenceDocumentId());
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
