package com.crosschecklab.domain.guardfit;

import com.crosschecklab.domain.audit.AuditAction;
import com.crosschecklab.domain.audit.AuditService;
import com.crosschecklab.domain.guardfit.dto.GuardFitActionCreateRequest;
import com.crosschecklab.domain.guardfit.dto.GuardFitActionCreatedResponse;
import com.crosschecklab.domain.guardfit.dto.GuardFitActionResponse;
import com.crosschecklab.domain.guardfit.dto.GuardFitActionUpdateRequest;
import com.crosschecklab.domain.risk.RiskPatternService;
import com.crosschecklab.global.common.PageResponse;
import com.crosschecklab.global.common.enums.GuardFitStatus;
import com.crosschecklab.global.common.enums.UserRole;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import com.crosschecklab.global.security.DemoUser;
import com.crosschecklab.global.security.OwnershipChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

// GuardFit 보호조치 유스케이스 (GF-001~003).
// 생성·편집·승인은 검토자 전용이고 상품 담당자는 승인본 조회만 할 수 있다(API 명세 §9 RBAC).
// 후보는 ACTIVE 패턴에만 붙고 DRAFT → APPROVED 로만 움직인다.
@Service
@RequiredArgsConstructor
public class GuardFitActionService {

    private final GuardFitActionRepository guardFitActionRepository;
    private final RiskPatternService riskPatternService;
    private final OwnershipChecker ownershipChecker;
    private final AuditService auditService;

    // GF-001. 사람이 승인해 ACTIVE 가 된 패턴에만 보호조치를 붙일 수 있다.
    @Transactional
    public GuardFitActionCreatedResponse create(GuardFitActionCreateRequest request, DemoUser currentUser) {
        ownershipChecker.requireRole(currentUser, UserRole.COMPLIANCE_REVIEWER);
        // 패턴 검증(존재·ACTIVE 여부)은 Risk 도메인이 소유한다. DRAFT 패턴이면 409 RISK_PATTERN_NOT_ACTIVE.
        riskPatternService.getActive(request.riskPatternId());

        GuardFitAction action = GuardFitAction.draft(
                request.riskPatternId(),
                request.actionType(),
                request.label().strip(),
                request.placement().strip(),
                request.requiredOrDefault(),
                normalizePreview(request.preview()),
                currentUser.id());
        GuardFitAction savedAction = guardFitActionRepository.save(action);
        auditService.append(
                currentUser,
                AuditAction.GUARDFIT_ACTION_CREATED,
                savedAction.getId(),
                savedAction.getLabel(),
                null);
        return GuardFitActionCreatedResponse.from(savedAction);
    }

    // GF-002. 상품 담당자와 검토자가 함께 쓰는 유일한 GuardFit 조회 경로다.
    @Transactional(readOnly = true)
    public PageResponse<GuardFitActionResponse> list(Long riskPatternId, GuardFitStatus status,
                                                     int page, int size, DemoUser currentUser) {
        Page<GuardFitAction> actions = guardFitActionRepository.findCatalog(
                riskPatternId,
                effectiveStatus(status, currentUser),
                // 정렬은 쿼리가 확정하므로 Pageable 에 별도 정렬을 얹지 않는다.
                PageRequest.of(page, size));
        return PageResponse.of(actions, GuardFitActionResponse::from);
    }

    // GF-003. 편집과 승인이 한 요청이며 APPROVED 이후에는 되돌리거나 고칠 수 없다.
    @Transactional
    public GuardFitActionResponse update(Long actionId, GuardFitActionUpdateRequest request, DemoUser currentUser) {
        ownershipChecker.requireRole(currentUser, UserRole.COMPLIANCE_REVIEWER);

        GuardFitAction action = guardFitActionRepository.findWithLockById(actionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        GuardFitStatus previousStatus = action.getStatus();
        // 이미 APPROVED 면 엔티티가 409 ACTION_ALREADY_FINALIZED 로 끊는다.
        action.edit(
                request.actionType(),
                request.label().strip(),
                request.placement().strip(),
                request.requiredOrDefault(),
                normalizePreview(request.preview()),
                request.status(),
                currentUser.id());
        // updatedAt 은 flush 시점에 채워지므로 응답에 최신 값을 담으려면 여기서 밀어낸다.
        GuardFitAction savedAction = guardFitActionRepository.saveAndFlush(action);
        auditService.append(
                currentUser,
                previousStatus == GuardFitStatus.DRAFT
                                && savedAction.getStatus() == GuardFitStatus.APPROVED
                        ? AuditAction.GUARDFIT_ACTION_APPROVED
                        : AuditAction.GUARDFIT_ACTION_UPDATED,
                savedAction.getId(),
                savedAction.getLabel(),
                null);
        return GuardFitActionResponse.from(savedAction);
    }

    // 상품 담당자에게는 승인본만 보인다. 요청에 status 를 무엇으로 넣든 서버가 APPROVED 로 고정한다.
    private GuardFitStatus effectiveStatus(GuardFitStatus requested, DemoUser currentUser) {
        return currentUser.isProductManager() ? GuardFitStatus.APPROVED : requested;
    }

    private String normalizePreview(String preview) {
        return StringUtils.hasText(preview) ? preview.strip() : null;
    }
}
