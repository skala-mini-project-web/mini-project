package com.crosschecklab.domain.analysis;

import com.crosschecklab.analysis.application.AnalysisService;
import com.crosschecklab.domain.analysis.dto.AnalysisAcceptedResponse;
import com.crosschecklab.domain.analysis.dto.AnalysisCreateRequest;
import com.crosschecklab.domain.analysis.dto.AnalysisResultResponse;
import com.crosschecklab.domain.analysis.dto.AnalysisStatusResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// ANA-001~004. 서비스 위임만 하고 비즈니스 로직을 두지 않는다.
// TODO(auth): 데모 인증 병합 후 @CurrentUser DemoUser 를 받아 역할(PRODUCT_MANAGER)·소유권 검증을 추가한다.
@RestController
@RequestMapping("/api/analyses")
@RequiredArgsConstructor
public class AnalysisController {

    private static final String SCENARIO_HEADER = "X-Demo-Scenario";

    private final AnalysisService analysisService;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AnalysisAcceptedResponse create(
            @Valid @RequestBody AnalysisCreateRequest request,
            @RequestHeader(value = SCENARIO_HEADER, required = false) String scenarioCode) {
        return analysisService.create(request, scenarioCode);
    }

    @GetMapping("/{analysisId}")
    public AnalysisStatusResponse getStatus(@PathVariable Long analysisId) {
        return analysisService.getStatus(analysisId);
    }

    @PostMapping("/{analysisId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AnalysisAcceptedResponse retry(
            @PathVariable Long analysisId,
            @RequestHeader(value = SCENARIO_HEADER, required = false) String scenarioCode) {
        return analysisService.retry(analysisId, scenarioCode);
    }

    @GetMapping("/{analysisId}/result")
    public AnalysisResultResponse getResult(@PathVariable Long analysisId) {
        return analysisService.getResult(analysisId);
    }
}
