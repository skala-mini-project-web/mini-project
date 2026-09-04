package com.crosschecklab.domain.review;

import com.crosschecklab.domain.review.dto.ReviewCreateRequest;
import com.crosschecklab.domain.review.dto.ReviewCreatedResponse;
import com.crosschecklab.domain.review.dto.ReviewDecisionRequest;
import com.crosschecklab.domain.review.dto.ReviewDecisionResponse;
import com.crosschecklab.domain.review.dto.ReviewDetailResponse;
import com.crosschecklab.domain.review.dto.ReviewListItemResponse;
import com.crosschecklab.global.common.PageResponse;
import com.crosschecklab.global.common.enums.ReviewStatus;
import com.crosschecklab.global.common.enums.Severity;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// REV-001~003. 서비스 위임만 하고 비즈니스 로직을 두지 않는다.
@Validated
@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ReviewService reviewService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewCreatedResponse create(@Valid @RequestBody ReviewCreateRequest request,
                                        @CurrentUser DemoUser currentUser) {
        return reviewService.create(request, currentUser);
    }

    @GetMapping
    public PageResponse<ReviewListItemResponse> list(
            @RequestParam(required = false) ReviewStatus status,
            @RequestParam(required = false) Severity severity,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @CurrentUser DemoUser currentUser) {
        return reviewService.list(status, severity, page, size, currentUser);
    }

    @GetMapping("/{reviewId}")
    public ReviewDetailResponse detail(@PathVariable Long reviewId,
                                       @CurrentUser DemoUser currentUser) {
        return reviewService.detail(reviewId, currentUser);
    }

    @PostMapping("/{reviewId}/decision")
    public ReviewDecisionResponse decide(@PathVariable Long reviewId,
                                         @Valid @RequestBody ReviewDecisionRequest request,
                                         @CurrentUser DemoUser currentUser) {
        return reviewService.decide(reviewId, request, currentUser);
    }
}
