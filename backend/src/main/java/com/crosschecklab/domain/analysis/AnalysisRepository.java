package com.crosschecklab.domain.analysis;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisRepository extends JpaRepository<Analysis, Long> {

    // 재시도는 두 요청이 동시에 FAILED 를 읽고 둘 다 통과하면 provider 를 중복 호출한다.
    // 행을 잠가 한 요청만 선점하게 하고, 뒤따르는 요청은 갱신된 상태를 읽어 409 로 끊긴다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Analysis> findWithLockById(Long id);

    // 소유권 검사를 잠금 전에 끝내기 위한 조회. 엔티티를 영속성 컨텍스트에 올리지 않아야
    // 뒤이은 findWithLockById 가 잠금과 함께 최신 상태를 다시 읽는다.
    @Query("select a.productDocumentId from Analysis a where a.id = :id")
    Optional<Long> findProductDocumentIdById(@Param("id") Long id);

    // 실행 회차 식별자. 재시도가 markRunning 으로 값을 바꾸므로, 뒤늦게 끝난 이전 회차가
    // 새 회차의 결과를 덮어쓰는 것을 막는 펜스로 쓴다. DB 값을 그대로 읽어 정밀도 차이를 없앤다.
    @Query("select a.updatedAt from Analysis a where a.id = :id")
    Optional<OffsetDateTime> findUpdatedAtById(@Param("id") Long id);
}
