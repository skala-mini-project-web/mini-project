package com.crosschecklab.domain.analysis.dto;

import com.crosschecklab.domain.analysis.Analysis;
import com.crosschecklab.global.common.enums.AnalysisStatus;

// 202 응답 (생성·재시도 공용)
public record AnalysisAcceptedResponse(
        Long analysisId,
        AnalysisStatus status,
        String statusUrl,
        String resultUrl
) {

    public static AnalysisAcceptedResponse created(Long analysisId) {
        return new AnalysisAcceptedResponse(
                analysisId,
                AnalysisStatus.CREATED,
                "/api/analyses/" + analysisId,
                "/api/analyses/" + analysisId + "/result");
    }

    public static AnalysisAcceptedResponse from(Analysis analysis) {
        return new AnalysisAcceptedResponse(
                analysis.getId(),
                analysis.getStatus(),
                "/api/analyses/" + analysis.getId(),
                "/api/analyses/" + analysis.getId() + "/result");
    }
}
