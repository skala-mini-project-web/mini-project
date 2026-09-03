package com.crosschecklab.domain.risk.dto;

import com.crosschecklab.domain.risk.RiskPattern;
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

    // RISK-002 응답. 목록(RISK-001)과 같은 모양을 써서 화면이 갱신된 행을 그대로 갈아끼울 수 있다.
    public static RiskPatternResponse from(RiskPattern pattern) {
        return new RiskPatternResponse(
                pattern.getId(),
                pattern.getFindingId(),
                pattern.getReviewId(),
                pattern.getName(),
                pattern.getSeverity(),
                pattern.getStatus());
    }

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
