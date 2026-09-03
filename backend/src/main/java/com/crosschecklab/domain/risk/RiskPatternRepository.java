package com.crosschecklab.domain.risk;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiskPatternRepository extends JpaRepository<RiskPattern, Long> {

    // RISK-002. 활성화는 현재 상태를 보고 다음 상태를 정하므로, 동시 요청이 둘 다 통과하지 않게 행을 잠근다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from RiskPattern p where p.id = :id")
    Optional<RiskPattern> findWithLockById(@Param("id") Long id);

    // RISK-001 Risk Library. 필터가 risk_patterns 에 없는 값(Persona·규칙)에 걸려 있어
    // finding → analysis 로 거슬러 올라가야 하므로 페이징까지 DB 에서 끝내는 네이티브 쿼리를 쓴다.
    // 각 필터는 null 이면 걸지 않으며, 파라미터 타입을 못 정하는 PostgreSQL 을 위해 CAST 로 타입을 명시한다.
    // ruleCode 는 findings 에 규칙 FK 가 없어 "그 규칙을 포함한 Red Team Pack 으로 분석된 패턴"으로 해석한다.
    @Query(value = """
            SELECT rp.id         AS riskPatternId,
                   rp.finding_id AS findingId,
                   rp.review_id  AS reviewId,
                   rp.name       AS name,
                   rp.severity   AS severity,
                   rp.status     AS status
            FROM risk_patterns rp
                     JOIN findings f ON f.id = rp.finding_id
                     JOIN analyses a ON a.id = f.analysis_id
            WHERE (CAST(:severity AS text) IS NULL OR rp.severity = CAST(:severity AS text))
              AND (CAST(:status AS text) IS NULL OR rp.status = CAST(:status AS text))
              AND (CAST(:personaCode AS text) IS NULL
                   OR EXISTS (SELECT 1
                              FROM finding_affected_personas fap
                                       JOIN persona_templates pt ON pt.id = fap.persona_template_id
                              WHERE fap.finding_id = f.id AND pt.code = CAST(:personaCode AS text)))
              AND (CAST(:ruleCode AS text) IS NULL
                   OR EXISTS (SELECT 1
                              FROM red_team_rules rtr
                              WHERE rtr.pack_id = a.red_team_pack_id
                                AND rtr.code = CAST(:ruleCode AS text)))
            ORDER BY CASE rp.severity WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END,
                     rp.created_at DESC,
                     rp.id DESC
            """,
            countQuery = """
                    SELECT count(*)
                    FROM risk_patterns rp
                             JOIN findings f ON f.id = rp.finding_id
                             JOIN analyses a ON a.id = f.analysis_id
                    WHERE (CAST(:severity AS text) IS NULL OR rp.severity = CAST(:severity AS text))
                      AND (CAST(:status AS text) IS NULL OR rp.status = CAST(:status AS text))
                      AND (CAST(:personaCode AS text) IS NULL
                           OR EXISTS (SELECT 1
                                      FROM finding_affected_personas fap
                                               JOIN persona_templates pt ON pt.id = fap.persona_template_id
                                      WHERE fap.finding_id = f.id AND pt.code = CAST(:personaCode AS text)))
                      AND (CAST(:ruleCode AS text) IS NULL
                           OR EXISTS (SELECT 1
                                      FROM red_team_rules rtr
                                      WHERE rtr.pack_id = a.red_team_pack_id
                                        AND rtr.code = CAST(:ruleCode AS text)))
                    """,
            nativeQuery = true)
    Page<RiskPatternListRow> findLibrary(@Param("severity") String severity,
                                         @Param("personaCode") String personaCode,
                                         @Param("ruleCode") String ruleCode,
                                         @Param("status") String status,
                                         Pageable pageable);
}
