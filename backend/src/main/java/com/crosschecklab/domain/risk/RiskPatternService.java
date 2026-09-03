package com.crosschecklab.domain.risk;

import com.crosschecklab.domain.analysis.Finding;
import com.crosschecklab.domain.risk.dto.RiskPatternResponse;
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
import org.springframework.util.StringUtils;

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
                                                  RedTeamRuleCode ruleCode, int page, int size,
                                                  DemoUser currentUser) {
        // Risk Library 는 검토자 전용이다. 상품 담당자가 호출하면 403.
        ownershipChecker.requireRole(currentUser, UserRole.COMPLIANCE_REVIEWER);

        Page<RiskPatternListRow> rows = riskPatternRepository.findLibrary(
                severity == null ? null : severity.name(),
                personaCode == null ? null : personaCode.name(),
                ruleCode == null ? null : ruleCode.name(),
                // 정렬은 쿼리가 확정하므로 Pageable 에 별도 정렬을 얹지 않는다.
                PageRequest.of(page, size));
        return PageResponse.of(rows, RiskPatternResponse::from);
    }

    // 검토 승인(REV-003)의 부수효과. 선택된 Finding 을 DRAFT 로 만든 뒤 검증을 통과한 것만 ACTIVE 로 올린다.
    // 호출자(ReviewService)의 트랜잭션에 참여하므로 검토 결정과 승격은 함께 커밋되거나 함께 취소된다.
    @Transactional
    public List<Long> promote(Long reviewId, List<Finding> findings) {
        List<RiskPattern> patterns = findings.stream()
                .map(finding -> RiskPattern.draft(
                        finding.getId(), reviewId, finding.getStatement(), finding.getSeverity()))
                .toList();
        patterns.stream().filter(RiskPatternService::passesLibraryCheck).forEach(RiskPattern::activate);
        try {
            // finding_id UNIQUE 위반(이미 승격된 Finding)을 여기서 드러낸다.
            return riskPatternRepository.saveAllAndFlush(patterns).stream().map(RiskPattern::getId).toList();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.INVALID_FINDING_SELECTION);
        }
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

    // 라이브러리 노출 기준. 이름 없이 승격된 패턴은 재사용할 수 없으므로 DRAFT 로 남겨 사람이 다듬게 한다.
    private static boolean passesLibraryCheck(RiskPattern pattern) {
        return StringUtils.hasText(pattern.getName());
    }
}
