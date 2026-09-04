package com.crosschecklab.domain.review;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    boolean existsByAnalysisId(Long analysisId);

    // REV-004. analysis_id 가 UNIQUE 라 분석 하나에 검토도 하나뿐이다.
    Optional<Review> findByAnalysisId(Long analysisId);

    // 결정은 1회뿐이라 두 요청이 동시에 PENDING 을 읽으면 RiskPattern 이 중복 생성된다.
    // 행을 잠가 한 요청만 선점하게 하고 뒤따르는 요청은 갱신된 status 를 읽어 409 로 끊긴다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Review r where r.id = :id")
    Optional<Review> findWithLockById(@Param("id") Long id);

    // REV-002 검토함. 정렬 기준(위험도 내림차순 → 제출시간 오름차순)이 조인·집계에 걸려 있어
    // 페이징을 DB 에서 끝내려면 네이티브 쿼리가 가장 단순하다.
    // status/severity 는 null 이면 필터를 걸지 않으며, 파라미터 타입을 못 정하는
    // PostgreSQL 을 위해 CAST 로 타입을 명시한다.
    @Query(value = """
            SELECT r.id            AS reviewId,
                   r.analysis_id   AS analysisId,
                   p.name          AS productName,
                   u.name          AS ownerName,
                   r.status        AS status,
                   ms.severity     AS maxSeverity
            FROM reviews r
                     JOIN analyses a ON a.id = r.analysis_id
                     JOIN product_documents d ON d.id = a.product_document_id
                     JOIN products p ON p.id = d.product_id
                     JOIN users u ON u.id = p.owner_id
                     LEFT JOIN LATERAL (
                         SELECT f.severity
                         FROM findings f
                         WHERE f.analysis_id = a.id
                         ORDER BY CASE f.severity WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END
                         LIMIT 1
                     ) ms ON TRUE
            WHERE (CAST(:status AS text) IS NULL OR r.status = CAST(:status AS text))
              AND (CAST(:severity AS text) IS NULL
                   OR EXISTS (SELECT 1 FROM findings f2
                              WHERE f2.analysis_id = a.id AND f2.severity = CAST(:severity AS text)))
            ORDER BY CASE ms.severity WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 3 ELSE 4 END,
                     r.created_at,
                     r.id
            """,
            countQuery = """
                    SELECT count(*)
                    FROM reviews r
                             JOIN analyses a ON a.id = r.analysis_id
                    WHERE (CAST(:status AS text) IS NULL OR r.status = CAST(:status AS text))
                      AND (CAST(:severity AS text) IS NULL
                           OR EXISTS (SELECT 1 FROM findings f2
                                      WHERE f2.analysis_id = a.id AND f2.severity = CAST(:severity AS text)))
                    """,
            nativeQuery = true)
    Page<ReviewListRow> findQueue(@Param("status") String status,
                                  @Param("severity") String severity,
                                  Pageable pageable);
}
