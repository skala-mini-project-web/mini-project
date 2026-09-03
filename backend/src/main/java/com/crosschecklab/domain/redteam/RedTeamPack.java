package com.crosschecklab.domain.redteam;

import com.crosschecklab.global.common.BaseTimeEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 위험 점검 규칙 묶음. MVP 는 CORE_FINANCIAL_RISK_V1 한 종류만 시드된다.
// code 는 Pack 이 늘어날 수 있어 Enum 으로 고정하지 않고 문자열로 둔다
// (규칙 code 는 분석 결과 매핑 키라서 RedTeamRuleCode Enum 으로 고정한다).
@Entity
@Getter
@Table(name = "red_team_packs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RedTeamPack extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private boolean active;

    // Pack 없이 존재하는 규칙은 없으므로 Pack 을 통해서만 읽는다 (읽기 전용, 저장은 시드가 담당).
    @OneToMany(mappedBy = "pack", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder asc, id asc")
    private List<RedTeamRule> rules = new ArrayList<>();
}
