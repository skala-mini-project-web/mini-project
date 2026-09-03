package com.crosschecklab.domain.risk;

import com.crosschecklab.domain.risk.dto.RiskPatternResponse;
import com.crosschecklab.global.common.PageResponse;
import com.crosschecklab.global.common.enums.PersonaCode;
import com.crosschecklab.global.common.enums.RedTeamRuleCode;
import com.crosschecklab.global.common.enums.Severity;
import com.crosschecklab.global.security.CurrentUser;
import com.crosschecklab.global.security.DemoUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// RISK-001. 서비스 위임만 하고 비즈니스 로직을 두지 않는다.
@Validated
@RestController
@RequestMapping("/api/risk-patterns")
@RequiredArgsConstructor
public class RiskPatternController {

    private static final int MAX_PAGE_SIZE = 100;

    private final RiskPatternService riskPatternService;

    @GetMapping
    public PageResponse<RiskPatternResponse> list(
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) PersonaCode personaCode,
            @RequestParam(required = false) RedTeamRuleCode ruleCode,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @CurrentUser DemoUser currentUser) {
        return riskPatternService.list(severity, personaCode, ruleCode, page, size, currentUser);
    }
}
