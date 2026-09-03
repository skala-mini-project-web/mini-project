package com.crosschecklab.analysis.provider;

import com.crosschecklab.analysis.provider.dto.AnalysisRequest;
import com.crosschecklab.analysis.provider.dto.AnalysisResult;

// 위험 분석 실행 포트. 호출자는 어떤 엔진(Mock ai-service / 향후 sLLM+RAG)이 붙는지 몰라야 한다.
// 구현체가 비동기 job 방식이더라도 내부에서 완료까지 처리하고 결과만 돌려준다.
public interface RiskAnalysisProvider {

    // 실패 시 ProviderException 을 던진다. 호출자는 errorCode/retryable 을 Analysis 에 기록한다.
    AnalysisResult analyze(AnalysisRequest request);
}
