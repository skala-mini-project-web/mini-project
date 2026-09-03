package com.crosschecklab.domain.risk.dto;

import com.crosschecklab.domain.risk.RiskPatternListRow;
import com.crosschecklab.global.common.enums.RiskPatternStatus;
import com.crosschecklab.global.common.enums.Severity;

// RISK-001 Risk Library 한 건.
// findingId·reviewId 를 함께 내려 화면에서 원본 Finding 과 Review 로 역추적할 수 있게 한다.
public record RiskPatternResponse(
        Long riskPatternId,
        Long findingId,
        Long reviewId,
        String name,
        Severity severity,
        RiskPatternStatus status
) {

    public static RiskPatternResponse from(RiskPatternListRow row) {
        return new RiskPatternResponse(
                row.getRiskPatternId(),
                row.getFindingId(),
                row.getReviewId(),
                row.getName(),
                Severity.valueOf(row.getSeverity()),
                RiskPatternStatus.valueOf(row.getStatus()));
    }
}
