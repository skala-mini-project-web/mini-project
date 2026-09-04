package com.crosschecklab.domain.guardfit;

import com.crosschecklab.domain.guardfit.dto.GuardFitActionCreateRequest;
import com.crosschecklab.domain.guardfit.dto.GuardFitActionCreatedResponse;
import com.crosschecklab.domain.guardfit.dto.GuardFitActionResponse;
import com.crosschecklab.domain.guardfit.dto.GuardFitActionUpdateRequest;
import com.crosschecklab.global.common.PageResponse;
import com.crosschecklab.global.common.enums.GuardFitStatus;
import com.crosschecklab.global.security.CurrentUser;
import com.crosschecklab.global.security.DemoUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// GF-001~003. 서비스 위임만 하고 비즈니스 로직을 두지 않는다.
@Validated
@RestController
@RequestMapping("/api/guardfit/actions")
@RequiredArgsConstructor
public class GuardFitActionController {

    private static final int MAX_PAGE_SIZE = 100;

    private final GuardFitActionService guardFitActionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GuardFitActionCreatedResponse create(@Valid @RequestBody GuardFitActionCreateRequest request,
                                                @CurrentUser DemoUser currentUser) {
        return guardFitActionService.create(request, currentUser);
    }

    // status 필터는 검토자에게만 의미가 있다. 상품 담당자 요청은 서비스가 APPROVED 로 고정한다.
    @GetMapping
    public PageResponse<GuardFitActionResponse> list(
            @RequestParam(required = false) Long riskPatternId,
            @RequestParam(required = false) GuardFitStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @CurrentUser DemoUser currentUser) {
        return guardFitActionService.list(riskPatternId, status, page, size, currentUser);
    }

    @PutMapping("/{actionId}")
    public GuardFitActionResponse update(@PathVariable Long actionId,
                                         @Valid @RequestBody GuardFitActionUpdateRequest request,
                                         @CurrentUser DemoUser currentUser) {
        return guardFitActionService.update(actionId, request, currentUser);
    }
}
