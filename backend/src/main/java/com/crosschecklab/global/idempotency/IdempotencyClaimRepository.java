package com.crosschecklab.global.idempotency;

import com.crosschecklab.global.idempotency.IdempotencyClaim.IdempotencyOperation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyClaimRepository extends JpaRepository<IdempotencyClaim, Long> {

    // ON CONFLICT는 동시 요청의 패자를 정상적인 0건 갱신으로 돌려준다.
    // unique 위반 예외가 없으므로 현재 트랜잭션이 rollback-only가 되지 않는다.
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_claims (
                actor_id, operation, idempotency_key, request_fingerprint, created_at, updated_at
            ) VALUES (
                :actorId, :#{#operation.name()}, :key, :fingerprint, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            ON CONFLICT (actor_id, operation, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int tryAcquire(@Param("actorId") Long actorId,
                   @Param("operation") IdempotencyOperation operation,
                   @Param("key") String key,
                   @Param("fingerprint") String fingerprint);

    // tryAcquire가 0을 반환한 패자는 승자의 커밋을 기다린 뒤 이 행을 잠가
    // 동일 fingerprint 재생과 다른 fingerprint 충돌을 일관된 상태에서 판정한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<IdempotencyClaim> findWithLockByActorIdAndOperationAndIdempotencyKey(
            Long actorId, IdempotencyOperation operation, String idempotencyKey);
}
