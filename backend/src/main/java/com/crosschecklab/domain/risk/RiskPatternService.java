package com.crosschecklab.domain.risk;

import com.crosschecklab.domain.analysis.Finding;
import com.crosschecklab.domain.risk.dto.RiskPatternResponse;
import com.crosschecklab.domain.risk.dto.RiskPatternUpdateRequest;
import com.crosschecklab.global.common.PageResponse;
import com.crosschecklab.global.common.enums.PersonaCode;
import com.crosschecklab.global.common.enums.RedTeamRuleCode;
import com.crosschecklab.global.common.enums.RiskPatternStatus;
import com.crosschecklab.global.common.enums.Severity;
import com.crosschecklab.global.common.enums.UserRole;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import com.crosschecklab.global.security.DemoUser;
import com.crosschecklab.global.security.OwnershipChecker;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Risk Library 유스케이스 (RISK-001) 와 승인 Finding 의 승격 규칙을 소유한다.
// 승격 자체는 검토 결정의 부수효과라 ReviewService 가 호출하지만,
// DRAFT 생성 → 검증 → ACTIVE 전환은 이 도메인이 단독으로 정한다.
@Service
@RequiredArgsConstructor
public class RiskPatternService {

    private final RiskPatternRepository riskPatternRepository;
    private final OwnershipChecker ownershipChecker;

    // RISK-001. 검토자 전용 조회. 위험도 높은 순 → 최근 승격 순으로 내려준다.
    @Transactional(readOnly = true)
    public PageResponse<RiskPatternResponse> list(Severity severity, PersonaCode personaCode,
                                                  RedTeamRuleCode ruleCode, RiskPatternStatus status,
                                                  int page, int size, DemoUser currentUser) {
        // Risk Library 는 검토자 전용이다. 상품 담당자가 호출하면 403.
        ownershipChecker.requireRole(currentUser, UserRole.COMPLIANCE_REVIEWER);

        Page<RiskPatternListRow> rows = riskPatternRepository.findLibrary(
                severity == null ? null : severity.name(),
                personaCode == null ? null : personaCode.name(),
                ruleCode == null ? null : ruleCode.name(),
                // status 를 생략하면 DRAFT·ACTIVE 를 함께 준다. 다듬을 초안만 보려면 DRAFT 로 거른다.
                status == null ? null : status.name(),
                // 정렬은 쿼리가 확정하므로 Pageable 에 별도 정렬을 얹지 않는다.
                PageRequest.of(page, size));
        return PageResponse.of(rows, RiskPatternResponse::from);
    }

    // 검토 승인(REV-003)의 부수효과. 선택된 Finding 을 항상 DRAFT 로 만든다.
    // 호출자(ReviewService)의 트랜잭션에 참여하므로 검토 결정과 승격은 함께 커밋되거나 함께 취소된다.
    //
    // 승인 즉시 ACTIVE 로 올리지 않는 이유: 이 시점의 name 은 Finding statement 를 255자로 자른 초안이고,
    // ACTIVE 가 되면 GuardFit 보호조치를 붙일 수 있는 상태가 된다.
    // 재사용 가능한 라이브러리 항목이 되려면 검토자가 이름을 다듬고 RISK-002 로 명시적으로 활성화해야 한다.
    @Transactional
    public List<Long> promote(Long reviewId, List<Finding> findings) {
        List<RiskPattern> patterns = findings.stream()
                .map(finding -> RiskPattern.draft(
                        finding.getId(), reviewId, finding.getStatement(), finding.getSeverity()))
                .toList();
        try {
            // finding_id UNIQUE 위반(이미 승격된 Finding)을 여기서 드러낸다.
            return riskPatternRepository.saveAllAndFlush(patterns).stream().map(RiskPattern::getId).toList();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.INVALID_FINDING_SELECTION);
        }
    }

    // RISK-002. 이름 다듬기와 활성화. 검토자 전용이며 DRAFT → ACTIVE 단방향이다.
    // 두 요청이 동시에 들어오면 둘 다 통과해 상태 판정이 어긋나므로 행을 잠그고 읽는다.
    @Transactional
    public RiskPatternResponse update(Long riskPatternId, RiskPatternUpdateRequest request, DemoUser currentUser) {
        ownershipChecker.requireRole(currentUser, UserRole.COMPLIANCE_REVIEWER);

        RiskPattern pattern = riskPatternRepository.findWithLockById(riskPatternId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        pattern.update(request.name(), request.status());

        // updatedAt 은 flush 시점에 채워지므로 응답에 최신 값을 담으려면 여기서 밀어낸다.
        return RiskPatternResponse.from(riskPatternRepository.saveAndFlush(pattern));
    }

    // GF-001·GF-003 진입점. 보호조치는 ACTIVE 패턴에만 붙일 수 있다.
    @Transactional(readOnly = true)
    public RiskPattern getActive(Long riskPatternId) {
        RiskPattern pattern = riskPatternRepository.findById(riskPatternId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (pattern.getStatus() != RiskPatternStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.RISK_PATTERN_NOT_ACTIVE);
        }
        return pattern;
    }

}
