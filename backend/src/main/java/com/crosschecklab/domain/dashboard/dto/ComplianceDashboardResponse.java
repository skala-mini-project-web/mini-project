package com.crosschecklab.domain.dashboard.dto;

import com.crosschecklab.domain.dashboard.ComplianceSummaryRow;
import com.crosschecklab.domain.review.dto.ReviewListItemResponse;
import java.util.List;

// DASH-002 검토자 대시보드. 상단 카드 네 개(summary)와 바로 열 검토 목록(priorityReviews)으로 나뉜다.
// priorityReviews 는 REV-002 검토함과 같은 행 모양을 쓴다. 행을 클릭하면 검토 상세로 그대로 이어진다.
public record ComplianceDashboardResponse(
        Summary summary,
        List<ReviewListItemResponse> priorityReviews
) {

    public static ComplianceDashboardResponse of(ComplianceSummaryRow row,
                                                 List<ReviewListItemResponse> priorityReviews) {
        return new ComplianceDashboardResponse(Summary.from(row), priorityReviews);
    }

    // decidedToday 는 조회 기간(from~to) 안에서 결정된 검토 수다. 기간을 주지 않으면 기본값이 오늘 하루라 이름 그대로 동작한다.
    public record Summary(
            long pendingReviews,
            long highFindings,
            long activeRiskPatterns,
            long decidedToday
    ) {

        public static Summary from(ComplianceSummaryRow row) {
            return new Summary(
                    row.getPendingReviews(),
                    row.getHighFindings(),
                    row.getActiveRiskPatterns(),
                    row.getDecidedInRange());
        }
    }
}
