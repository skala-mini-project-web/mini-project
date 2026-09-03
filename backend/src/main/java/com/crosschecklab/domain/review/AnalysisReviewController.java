package com.crosschecklab.domain.review;

import com.crosschecklab.domain.review.dto.ReviewOutcomeResponse;
import com.crosschecklab.global.security.CurrentUser;
import com.crosschecklab.global.security.DemoUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// REV-004. 분석에 딸린 검토 결과를 분석 리소스 경로로 노출한다.
//
// 경로는 /api/analyses 아래지만 Review 도메인이 소유한다.
// AnalysisController 에 두면 분석 도메인이 검토 도메인을 알게 되므로 방향을 뒤집지 않는다.
@Tag(name = "Review", description = "검토")
@RestController
@RequestMapping("/api/analyses")
@RequiredArgsConstructor
public class AnalysisReviewController {

    private final ReviewService reviewService;

    @GetMapping("/{analysisId}/review")
    @Operation(summary = "REV-004 분석의 검토 결과 조회",
            description = "소유자 본인 또는 COMPLIANCE_REVIEWER 만 조회할 수 있다. "
                    + "상품 담당자가 반려 사유(comment)를 확인하는 경로다. "
                    + "검토 요청 전이면 404 REVIEW_NOT_FOUND, 결정 전이면 status=PENDING 이고 "
                    + "reviewerId·decidedAt·comment 가 null 이다. 어떤 상태도 변경하지 않는다.")
    public ResponseEntity<ReviewOutcomeResponse> findByAnalysis(@PathVariable Long analysisId,
                                                                @CurrentUser DemoUser currentUser) {
        return ResponseEntity.ok(reviewService.findByAnalysis(analysisId, currentUser));
    }
}
