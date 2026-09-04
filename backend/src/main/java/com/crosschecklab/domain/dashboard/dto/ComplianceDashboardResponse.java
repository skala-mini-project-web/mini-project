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

    // decidedInRange 는 조회 기간(from~to) 안에서 결정된 검토 수다. 기간을 생략하면 오늘 하루가 기본값이다.
    // from·to 를 주면 오늘이 아닌 그 기간의 값이므로 이름에 Today 를 쓰지 않는다.
    public record Summary(
            long pendingReviews,
            long highFindings,
            long activeRiskPatterns,
            long decidedInRange
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
