package com.crosschecklab.domain.guardfit;

import com.crosschecklab.global.common.enums.GuardFitStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuardFitActionRepository extends JpaRepository<GuardFitAction, Long> {

    // GF-003. 승인은 1회뿐이라 두 요청이 동시에 DRAFT 를 읽으면 둘 다 통과해 버린다.
    // 행을 잠가 한 요청만 선점하게 하고 뒤따르는 요청은 갱신된 status 를 읽어 409 로 끊긴다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from GuardFitAction a where a.id = :id")
    Optional<GuardFitAction> findWithLockById(@Param("id") Long id);

    // GF-002. 두 필터 모두 null 이면 걸지 않는다.
    // status 는 상품 담당자일 때 서버가 APPROVED 로 고정해서 넘긴다(GuardFitActionService).
    // 정렬은 패턴별로 묶어 보는 Before/After 가이드 화면에 맞춰 패턴 → 생성 순으로 고정한다.
    @Query("""
            select a from GuardFitAction a
            where (:riskPatternId is null or a.riskPatternId = :riskPatternId)
              and (:status is null or a.status = :status)
            order by a.riskPatternId asc, a.id asc
            """)
    Page<GuardFitAction> findCatalog(@Param("riskPatternId") Long riskPatternId,
                                     @Param("status") GuardFitStatus status,
                                     Pageable pageable);
}
