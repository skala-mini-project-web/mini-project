package com.crosschecklab.domain.risk;

import com.crosschecklab.domain.risk.dto.RiskPatternResponse;
import com.crosschecklab.domain.risk.dto.RiskPatternUpdateRequest;
import com.crosschecklab.global.common.PageResponse;
import com.crosschecklab.global.common.enums.PersonaCode;
import com.crosschecklab.global.common.enums.RedTeamRuleCode;
import com.crosschecklab.global.common.enums.RiskPatternStatus;
import com.crosschecklab.global.common.enums.Severity;
import com.crosschecklab.global.security.CurrentUser;
import com.crosschecklab.global.security.DemoUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// RISK-001~002. 서비스 위임만 하고 비즈니스 로직을 두지 않는다.
@Tag(name = "RiskPattern", description = "Risk Library")
@Validated
@RestController
@RequestMapping("/api/risk-patterns")
@RequiredArgsConstructor
public class RiskPatternController {

    private static final int MAX_PAGE_SIZE = 100;

    private final RiskPatternService riskPatternService;

    @GetMapping
    @Operation(summary = "RISK-001 Risk Library 조회",
            description = "검토자 전용이다. status 를 생략하면 DRAFT·ACTIVE 를 함께 반환하고, "
                    + "DRAFT 로 거르면 아직 다듬지 않은 승격 초안만 볼 수 있다.")
    public PageResponse<RiskPatternResponse> list(
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) PersonaCode personaCode,
            @RequestParam(required = false) RedTeamRuleCode ruleCode,
            @RequestParam(required = false) RiskPatternStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @CurrentUser DemoUser currentUser) {
        return riskPatternService.list(severity, personaCode, ruleCode, status, page, size, currentUser);
    }

    @PatchMapping("/{riskPatternId}")
    @Operation(summary = "RISK-002 패턴 이름 수정·활성화",
            description = "검토자 전용이다. name 과 status 는 모두 선택이며 보낸 것만 반영한다. "
                    + "status=ACTIVE 로 올려야 GuardFit 보호조치를 붙일 수 있다. "
                    + "DRAFT 로 되돌리면 409 RISK_PATTERN_ALREADY_ACTIVE 다.")
    public RiskPatternResponse update(@PathVariable Long riskPatternId,
                                      @Valid @RequestBody RiskPatternUpdateRequest request,
                                      @CurrentUser DemoUser currentUser) {
        return riskPatternService.update(riskPatternId, request, currentUser);
    }
}
