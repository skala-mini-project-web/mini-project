package com.crosschecklab.domain.dashboard;

import com.crosschecklab.domain.dashboard.dto.DashboardSummaryResponse;
import com.crosschecklab.global.security.DemoUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final DashboardRepository dashboardRepository;

    // DASH-001. 요청자가 소유한 상품만 집계한다.
    // 역할로 막지 않는다. 검토자가 호출하면 소유 상품이 없어 자연히 전부 0 이 나오고,
    // 검토자용 집계는 /api/dashboard/compliance 가 따로 담당한다.
    public DashboardSummaryResponse summarizeMine(DemoUser currentUser) {
        return DashboardSummaryResponse.from(dashboardRepository.summarize(currentUser.id()));
    }
}
