package com.crosschecklab.domain.redteam;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RedTeamPackRepository extends JpaRepository<RedTeamPack, Long> {

    // rules 를 fetch join 해 Pack 수만큼 추가 쿼리가 나가는 것을 막는다.
    // @OrderBy 는 컬렉션 정렬만 담당하므로 Pack 순서는 여기서 지정한다.
    @Query("""
            select distinct p from RedTeamPack p
            left join fetch p.rules
            where (:active is null or p.active = :active)
            order by p.id asc
            """)
    List<RedTeamPack> findAllWithRules(@Param("active") Boolean active);
}
