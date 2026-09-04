package com.crosschecklab.domain.analysis;

import com.crosschecklab.analysis.provider.ProviderException;
import com.crosschecklab.analysis.provider.RiskAnalysisProvider;
import com.crosschecklab.analysis.provider.dto.AnalysisRequest;
import com.crosschecklab.analysis.provider.dto.AnalysisResult;
import com.crosschecklab.analysis.provider.dto.FindingPayload;
import com.crosschecklab.global.common.enums.PersonaCode;
import com.crosschecklab.global.common.enums.Severity;
import com.crosschecklab.global.error.ErrorCode;
import java.util.List;
import java.util.function.Function;

// 외부 ai-service 없이 분석 흐름을 검증하기 위한 테스트 대역.
// ai-service fixture(guarantee_high.json)와 같은 riskScore 82 시나리오를 기본값으로 돌려준다.
class FakeRiskAnalysisProvider implements RiskAnalysisProvider {

    private Function<AnalysisRequest, AnalysisResult> behavior = FakeRiskAnalysisProvider::guaranteeHigh;
    private AnalysisRequest lastRequest;

    @Override
    public AnalysisResult analyze(AnalysisRequest request) {
        this.lastRequest = request;
        return behavior.apply(request);
    }

    AnalysisRequest lastRequest() {
        return lastRequest;
    }

    void reset() {
        behavior = FakeRiskAnalysisProvider::guaranteeHigh;
        lastRequest = null;
    }

    void failWith(ErrorCode errorCode, boolean retryable) {
        behavior = request -> {
            throw new ProviderException(errorCode, retryable, "test");
        };
    }

    private static AnalysisResult guaranteeHigh(AnalysisRequest request) {
        AnalysisRequest.RetrievedContextPayload context = request.retrievedContexts().getFirst();
        return new AnalysisResult(82, "mock-risk-v1", "mock-prompt-v1", List.of(new FindingPayload(
                "안정성 표현이 원금보장으로 오인될 가능성이 있습니다.",
                Severity.HIGH,
                List.of(PersonaCode.FINANCIAL_BEGINNER, PersonaCode.SENIOR),
                List.of(context.chunkId()),
                "안정성 표현과 같은 영역에 원금 손실 가능성을 명시하세요.")));
    }
}
