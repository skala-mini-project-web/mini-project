package com.crosschecklab.domain.dashboard;

import com.crosschecklab.domain.dashboard.dto.ComplianceDashboardResponse;
import com.crosschecklab.domain.dashboard.dto.DashboardSummaryResponse;
import com.crosschecklab.global.security.CurrentUser;
import com.crosschecklab.global.security.DemoUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Dashboard", description = "대시보드")
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/me")
    @Operation(summary = "DASH-001 담당자 대시보드 요약",
            description = "요청자가 소유한 상품의 진행 상황을 집계한다. 어떤 상태도 변경하지 않는다.")
    public ResponseEntity<DashboardSummaryResponse> summarizeMine(@CurrentUser DemoUser currentUser) {
        return ResponseEntity.ok(dashboardService.summarizeMine(currentUser));
    }

    @GetMapping("/compliance")
    @Operation(summary = "DASH-002 검토자 대시보드 요약",
            description = "검토 대기·HIGH Finding·활성 패턴·기간 내 결정 건수를 집계하고 우선 검토 목록을 함께 내려준다. "
                    + "from·to(yyyy-MM-dd, 양끝 포함)는 decidedInRange 의 조회 기간이며 생략하면 오늘 하루다. "
                    + "어떤 상태도 변경하지 않는다.")
    public ResponseEntity<ComplianceDashboardResponse> summarizeCompliance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @CurrentUser DemoUser currentUser) {
        return ResponseEntity.ok(dashboardService.summarizeCompliance(from, to, currentUser));
    }
}
