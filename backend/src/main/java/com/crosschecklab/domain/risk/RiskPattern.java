package com.crosschecklab.domain.risk;

import com.crosschecklab.global.common.BaseTimeEntity;
import com.crosschecklab.global.common.enums.RiskPatternStatus;
import com.crosschecklab.global.common.enums.Severity;
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

    // 검토 승인 시점에는 DRAFT 로 만들고, 검증을 통과한 뒤에만 ACTIVE 로 올린다.
    public static RiskPattern draft(Long findingId, Long reviewId, String name, Severity severity) {
        RiskPattern pattern = new RiskPattern();
        pattern.findingId = findingId;
        pattern.reviewId = reviewId;
        pattern.name = truncate(name);
        pattern.severity = severity;
        pattern.status = RiskPatternStatus.DRAFT;
        return pattern;
    }

    public void activate() {
        this.status = RiskPatternStatus.ACTIVE;
    }

    private static String truncate(String name) {
        String trimmed = name.strip();
        return trimmed.length() <= NAME_MAX_LENGTH ? trimmed : trimmed.substring(0, NAME_MAX_LENGTH);
    }
}
