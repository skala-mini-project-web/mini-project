package com.crosschecklab.domain.dashboard;

import com.crosschecklab.domain.dashboard.dto.ComplianceDashboardResponse;
import com.crosschecklab.domain.dashboard.dto.DashboardSummaryResponse;
import com.crosschecklab.domain.review.ReviewListRow;
import com.crosschecklab.domain.review.ReviewRepository;
import com.crosschecklab.domain.review.dto.ReviewListItemResponse;
import com.crosschecklab.global.common.enums.ReviewStatus;
import com.crosschecklab.global.common.enums.UserRole;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import com.crosschecklab.global.security.DemoUser;
import com.crosschecklab.global.security.OwnershipChecker;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    // 대시보드 카드 옆에 붙는 목록이라 검토함(REV-002) 전체가 아니라 맨 위 몇 건만 보여준다.
    // 나머지는 "검토함 열기" 로 이어진다.
    private static final int PRIORITY_REVIEW_LIMIT = 5;

    private final DashboardRepository dashboardRepository;
    private final ReviewRepository reviewRepository;
    private final OwnershipChecker ownershipChecker;
    private final Clock clock;

    // DASH-001. 요청자가 소유한 상품만 집계한다.
    // 역할로 막지 않는다. 검토자가 호출하면 소유 상품이 없어 자연히 전부 0 이 나오고,
    // 검토자용 집계는 /api/dashboard/compliance 가 따로 담당한다.
    public DashboardSummaryResponse summarizeMine(DemoUser currentUser) {
        return DashboardSummaryResponse.from(dashboardRepository.summarize(currentUser.id()));
    }

    // DASH-002. 검토 업무 전체를 집계한다. 소유자 개념이 없어 검토자 전용이다.
    // from·to 는 결정 건수(decidedInRange)의 조회 창이며, 생략하면 오늘 하루다.
    // priorityReviews 는 지금 남은 대기열이라 기간과 무관하게 항상 현재 PENDING 을 보여준다.
    public ComplianceDashboardResponse summarizeCompliance(LocalDate from, LocalDate to, DemoUser currentUser) {
        // 검토자 전용이다. 상품 담당자가 호출하면 403.
        ownershipChecker.requireRole(currentUser, UserRole.COMPLIANCE_REVIEWER);

        LocalDate today = LocalDate.now(clock);
        LocalDate fromDate = from == null ? today : from;
        LocalDate toDate = to == null ? today : to;
        if (fromDate.isAfter(toDate)) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE);
        }

        ComplianceSummaryRow summary = dashboardRepository.summarizeCompliance(
                startOfDay(fromDate), startOfDay(toDate.plusDays(1)));
        return ComplianceDashboardResponse.of(summary, priorityReviews());
    }

    // 정렬 규칙(위험도 내림차순 → 제출시간 오름차순)은 검토함 쿼리가 이미 소유한다.
    // 대시보드가 같은 규칙을 다시 적으면 두 화면의 "가장 급한 건" 이 어긋나므로 그대로 재사용한다.
    private List<ReviewListItemResponse> priorityReviews() {
        List<ReviewListRow> rows = reviewRepository.findQueue(
                ReviewStatus.PENDING.name(), null, PageRequest.of(0, PRIORITY_REVIEW_LIMIT)).getContent();
        return rows.stream().map(ReviewListItemResponse::from).toList();
    }

    // to 는 사용자에게 포함(inclusive) 이지만 쿼리는 [fromAt, toAt) 반열림이라 하루를 더해 넘긴다.
    // 날짜 경계는 서버 Clock 의 시간대를 기준으로 한다.
    private OffsetDateTime startOfDay(LocalDate date) {
        return date.atStartOfDay(clock.getZone()).toOffsetDateTime();
    }
}
