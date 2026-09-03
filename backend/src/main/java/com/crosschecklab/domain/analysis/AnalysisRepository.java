package com.crosschecklab.domain.analysis;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AnalysisRepository extends JpaRepository<Analysis, Long> {

    // 재시도는 두 요청이 동시에 FAILED 를 읽고 둘 다 통과하면 provider 를 중복 호출한다.
    // 행을 잠가 한 요청만 선점하게 하고, 뒤따르는 요청은 갱신된 상태를 읽어 409 로 끊긴다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Analysis> findWithLockById(Long id);
}
