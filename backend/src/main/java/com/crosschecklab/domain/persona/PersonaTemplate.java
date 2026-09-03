package com.crosschecklab.domain.persona;

import com.crosschecklab.global.common.BaseTimeEntity;
import com.crosschecklab.global.common.enums.PersonaCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 고정 AI 소비자 Persona 5종. 행은 V2 시드로만 생성되며 MVP 에서는 조회만 한다.
// criteria / risk_focus 는 jsonb 컬럼이라 SqlTypes.JSON 으로 매핑한다.
// (criteria 는 키가 Persona 마다 달라 고정 DTO 로 만들지 않고 Map 으로 그대로 내린다)
@Entity
@Getter
@Table(name = "persona_templates")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PersonaTemplate extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 40)
    private PersonaCode code;

    @Column(nullable = false, length = 100)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> criteria;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "risk_focus", columnDefinition = "jsonb")
    private List<String> riskFocus;

    @Column(nullable = false)
    private boolean active;
}
