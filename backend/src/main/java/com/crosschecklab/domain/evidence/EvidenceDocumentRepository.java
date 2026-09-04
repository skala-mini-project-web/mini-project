package com.crosschecklab.domain.evidence;

import com.crosschecklab.global.common.enums.EvidenceSourceType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EvidenceDocumentRepository extends JpaRepository<EvidenceDocument, Long> {

    // sourceType / active 는 선택 필터라 null 이면 조건에서 빠진다.
    // 목록 순서를 id 로 고정해 FE 가 매번 같은 순서를 받도록 한다.
    @Query("""
            select e from EvidenceDocument e
            where (:sourceType is null or e.sourceType = :sourceType)
              and (:active is null or e.active = :active)
            order by e.id asc
            """)
    List<EvidenceDocument> search(@Param("sourceType") EvidenceSourceType sourceType,
                                  @Param("active") Boolean active);
}
