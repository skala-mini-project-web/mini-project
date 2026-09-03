package com.crosschecklab.domain.dashboard.dto;

import com.crosschecklab.domain.dashboard.DashboardSummaryRow;

// DASH-001 담당자 대시보드 요약. 화면 상단 카드 네 개에 그대로 대응한다.
public record DashboardSummaryResponse(
        long myProducts,
        long analyzing,
        long pendingReview,
        long approved
) {

    public static DashboardSummaryResponse from(DashboardSummaryRow row) {
        return new DashboardSummaryResponse(
                row.getMyProducts(), row.getAnalyzing(), row.getPendingReview(), row.getApproved());
    }
}
