package com.crosschecklab.domain.review;

import com.crosschecklab.domain.analysis.Analysis;
import com.crosschecklab.domain.analysis.AnalysisRepository;
import com.crosschecklab.domain.analysis.Finding;
import com.crosschecklab.domain.analysis.FindingRepository;
import com.crosschecklab.domain.document.ProductDocument;
import com.crosschecklab.domain.document.ProductDocumentRepository;
import com.crosschecklab.domain.review.dto.ReviewCreateRequest;
import com.crosschecklab.domain.review.dto.ReviewCreatedResponse;
import com.crosschecklab.domain.review.dto.ReviewDecisionRequest;
import com.crosschecklab.domain.review.dto.ReviewDecisionResponse;
import com.crosschecklab.domain.review.dto.ReviewListItemResponse;
import com.crosschecklab.domain.risk.RiskPatternService;
import com.crosschecklab.global.common.PageResponse;
import com.crosschecklab.global.common.enums.ReviewStatus;
import com.crosschecklab.global.common.enums.Severity;
import com.crosschecklab.global.common.enums.UserRole;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import com.crosschecklab.global.security.DemoUser;
import com.crosschecklab.global.security.OwnershipChecker;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

// 검토 유스케이스 (REV-001~003).
// AI/Mock 결과가 사람의 승인 없이 Risk Library 로 넘어가지 않도록 이 서비스가 유일한 승격 경로다.
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final RiskPatternService riskPatternService;
    private final AnalysisRepository analysisRepository;
    private final FindingRepository findingRepository;
    private final ProductDocumentRepository productDocumentRepository;
    private final OwnershipChecker ownershipChecker;
    private final Clock clock;

    // REV-001. 완료된 분석을 검토 대기열에 올리고 분석을 IN_REVIEW 로 넘긴다.
    @Transactional
    public ReviewCreatedResponse create(ReviewCreateRequest request, DemoUser currentUser) {
        ownershipChecker.requireRole(currentUser, UserRole.PRODUCT_MANAGER);

        Analysis analysis = analysisRepository.findById(request.analysisId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // 분석 상태(409)를 알려주기 전에 소유권부터 판정한다.
        ownershipChecker.requireOwner(ownerIdOf(analysis.getProductDocumentId()), currentUser);

        if (reviewRepository.existsByAnalysisId(analysis.getId())) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_EXISTS);
        }
        analysis.markInReview();

        Review review = Review.create(analysis.getId());
        try {
            // 같은 분석에 동시에 두 요청이 들어오면 analysis_id UNIQUE 가 잡아낸다.
            reviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_EXISTS);
        }
        return ReviewCreatedResponse.from(review);
    }

    // REV-002. 검토자 전용 대기열. PENDING 은 위험도 내림차순 → 제출시간 오름차순으로 정렬된다.
    @Transactional(readOnly = true)
    public PageResponse<ReviewListItemResponse> list(ReviewStatus status, Severity severity,
                                                     int page, int size, DemoUser currentUser) {
        // 검토함은 검토자 전용이다. 상품 담당자가 호출하면 403.
        ownershipChecker.requireRole(currentUser, UserRole.COMPLIANCE_REVIEWER);

        Page<ReviewListRow> rows = reviewRepository.findQueue(
                status == null ? null : status.name(),
                severity == null ? null : severity.name(),
                // 정렬은 쿼리가 확정하므로 Pageable 에 별도 정렬을 얹지 않는다.
                PageRequest.of(page, size));
        return PageResponse.of(rows, ReviewListItemResponse::from);
    }

    // REV-003. 승인이면 선택한 Finding 만 RiskPattern 으로 승격한다. 결정은 1회뿐이다.
    @Transactional
    public ReviewDecisionResponse decide(Long reviewId, ReviewDecisionRequest request, DemoUser currentUser) {
        ownershipChecker.requireRole(currentUser, UserRole.COMPLIANCE_REVIEWER);
        ReviewStatus decision = requireDecisionStatus(request.status());

        Review review = reviewRepository.findWithLockById(reviewId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        Set<Long> selectedFindingIds = validateSelection(review, decision, request);
        review.decide(decision, currentUser.id(), normalizeComment(request.comment()),
                selectedFindingIds, OffsetDateTime.now(clock));

        // 승격 규칙(DRAFT 생성 → 검증 → ACTIVE)은 Risk 도메인이 소유한다.
        // 승격 이력은 finding_id · review_id 로 원본까지 역추적할 수 있다.
        List<Long> riskPatternIds = decision == ReviewStatus.APPROVED
                ? riskPatternService.promote(review.getId(), findingRepository.findAllById(selectedFindingIds))
                : List.of();
        return ReviewDecisionResponse.of(review, riskPatternIds);
    }

    // 결정 조합 검증. 승인은 Finding 선택이, 반려는 사유가 필수다.
    private Set<Long> validateSelection(Review review, ReviewStatus decision, ReviewDecisionRequest request) {
        if (decision == ReviewStatus.REJECTED) {
            if (!StringUtils.hasText(request.comment())) {
                throw new BusinessException(ErrorCode.COMMENT_REQUIRED);
            }
            return Set.of();
        }

        Set<Long> selected = new LinkedHashSet<>(request.selectedFindingIdsOrEmpty());
        if (selected.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_FINDING_SELECTION);
        }
        // 다른 분석의 Finding 을 끼워 넣어 승격시키지 못하게 한다.
        Set<Long> ownFindingIds = findingRepository.findByAnalysisIdOrderByIdAsc(review.getAnalysisId()).stream()
                .map(Finding::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!ownFindingIds.containsAll(selected)) {
            throw new BusinessException(ErrorCode.INVALID_FINDING_SELECTION);
        }
        return selected;
    }

    private ReviewStatus requireDecisionStatus(ReviewStatus status) {
        if (status != ReviewStatus.APPROVED && status != ReviewStatus.REJECTED) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return status;
    }

    private String normalizeComment(String comment) {
        return StringUtils.hasText(comment) ? comment.strip() : null;
    }

    // 소유자는 analysis → document → product → owner 로 파생한다.
    private Long ownerIdOf(Long productDocumentId) {
        ProductDocument document = productDocumentRepository.findById(productDocumentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return document.getProduct().getOwnerId();
    }
}
