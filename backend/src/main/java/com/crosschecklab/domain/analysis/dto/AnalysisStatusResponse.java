package com.crosschecklab.domain.analysis.dto;

import com.crosschecklab.domain.analysis.Analysis;
import com.crosschecklab.global.common.enums.AnalysisStatus;
import com.crosschecklab.global.error.ErrorCode;
import java.time.OffsetDateTime;

// ANA-002. 202 로 수락된 뒤 실패한 작업도 여기서 HTTP 200 + FAILED 로 내려간다.
public record AnalysisStatusResponse(
        Long analysisId,
        AnalysisStatus status,
        int progress,
        Integer riskScore,
        boolean requiresHumanApproval,
        boolean retryable,
        String errorCode,
        String message,
        OffsetDateTime updatedAt
) {

    public static AnalysisStatusResponse from(Analysis analysis) {
        String errorCode = analysis.getErrorCode();
        return new AnalysisStatusResponse(
                analysis.getId(),
                analysis.getStatus(),
                analysis.getProgress(),
                analysis.getRiskScore(),
                analysis.isRequiresHumanApproval(),
                analysis.isRetryable(),
                errorCode,
                errorCode == null ? null : ErrorCode.valueOf(errorCode).getDefaultMessage(),
                analysis.getUpdatedAt());
    }
}
