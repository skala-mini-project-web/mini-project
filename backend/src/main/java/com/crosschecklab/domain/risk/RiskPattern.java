package com.crosschecklab.domain.risk;

import com.crosschecklab.global.common.BaseTimeEntity;
import com.crosschecklab.global.common.enums.RiskPatternStatus;
import com.crosschecklab.global.common.enums.Severity;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

// 승인된 Finding 만 승격되는 재사용 가능한 위험 패턴.
// finding_id 가 UNIQUE 라 하나의 Finding 은 한 번만 승격된다.
// 영향 Persona·근거 인용은 여기에 복사하지 않고 finding_id 로 역추적한다.
@Entity
@Getter
@Table(name = "risk_patterns")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiskPattern extends BaseTimeEntity {

    // name 컬럼이 VARCHAR(255) 라 Finding statement 를 그대로 넣으면 넘칠 수 있다.
    private static final int NAME_MAX_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "finding_id", nullable = false, updatable = false, unique = true)
    private Long findingId;

    // 어느 검토에서 승격됐는지. 검토가 지워져도 패턴은 남으므로(ON DELETE SET NULL) nullable 이다.
    @Column(name = "review_id")
    private Long reviewId;

    @Column(nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RiskPatternStatus status;

    // 검토 승인 시점에는 항상 DRAFT 로 만든다 (기능 명세 RISK-001).
    // name 은 Finding statement 를 자른 초안이라 그대로 라이브러리에 노출하기엔 길다.
    // 검토자가 RISK-002 로 이름을 다듬고 ACTIVE 로 올린다.
    public static RiskPattern draft(Long findingId, Long reviewId, String name, Severity severity) {
        RiskPattern pattern = new RiskPattern();
        pattern.findingId = findingId;
        pattern.reviewId = reviewId;
        pattern.name = truncate(name);
        pattern.severity = severity;
        pattern.status = RiskPatternStatus.DRAFT;
        return pattern;
    }

    // RISK-002. 이름 다듬기와 활성화가 한 요청이다.
    // 이름은 ACTIVE 이후에도 고칠 수 있다 (라이브러리 큐레이션은 계속된다).
    // 상태는 DRAFT → ACTIVE 단방향이며 되돌릴 수 없다 (API 명세 §1.2).
    public void update(String name, RiskPatternStatus decision) {
        if (name != null) {
            // 공백만 보내 이름을 지우는 것은 허용하지 않는다. 라이브러리 항목은 항상 이름을 갖는다.
            String trimmed = truncate(name);
            if (!StringUtils.hasText(trimmed)) {
                throw new BusinessException(ErrorCode.RISK_PATTERN_NAME_REQUIRED);
            }
            this.name = trimmed;
        }
        if (decision == null || decision == this.status) {
            return;
        }
        if (decision == RiskPatternStatus.DRAFT) {
            throw new BusinessException(ErrorCode.RISK_PATTERN_ALREADY_ACTIVE);
        }
        this.status = RiskPatternStatus.ACTIVE;
    }

    private static String truncate(String name) {
        String trimmed = name.strip();
        return trimmed.length() <= NAME_MAX_LENGTH ? trimmed : trimmed.substring(0, NAME_MAX_LENGTH);
    }
}
