package com.crosschecklab.domain.dashboard;

import com.crosschecklab.domain.dashboard.dto.DashboardSummaryResponse;
import com.crosschecklab.global.security.CurrentUser;
import com.crosschecklab.global.security.DemoUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
