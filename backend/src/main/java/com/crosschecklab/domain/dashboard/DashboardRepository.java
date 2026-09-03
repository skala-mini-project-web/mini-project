package com.crosschecklab.domain.dashboard;

import com.crosschecklab.domain.product.Product;
import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 대시보드는 카운트만 필요해 읽기 전용 집계 하나만 노출한다.
// JpaRepository 를 상속하면 쓰기 메서드까지 딸려오므로 Repository 마커만 상속한다.
public interface DashboardRepository extends Repository<Product, Long> {

    // 네 카운트를 각각 조회하면 왕복이 네 번이라 스칼라 서브쿼리로 한 번에 읽는다.
    // 상품에서 검토까지는 products → product_documents → analyses → reviews 로 이어진다.
    // 상태 문자열은 VARCHAR + CHECK 컬럼이라 Enum 캐스팅 없이 그대로 비교한다.
    // 집계 대상이 없으면 count 가 0 을 돌려주므로 행 자체는 항상 하나 나온다.
    @Query(value = """
            SELECT (SELECT count(*) FROM products p
                     WHERE p.owner_id = :ownerId)                        AS myProducts,
                   (SELECT count(*) FROM analyses a
                             JOIN product_documents d ON d.id = a.product_document_id
                             JOIN products p ON p.id = d.product_id
                     WHERE p.owner_id = :ownerId
                       AND a.status IN ('CREATED', 'RUNNING'))           AS analyzing,
                   (SELECT count(*) FROM reviews r
                             JOIN analyses a ON a.id = r.analysis_id
                             JOIN product_documents d ON d.id = a.product_document_id
                             JOIN products p ON p.id = d.product_id
                     WHERE p.owner_id = :ownerId AND r.status = 'PENDING')  AS pendingReview,
                   (SELECT count(*) FROM reviews r
                             JOIN analyses a ON a.id = r.analysis_id
                             JOIN product_documents d ON d.id = a.product_document_id
                             JOIN products p ON p.id = d.product_id
                     WHERE p.owner_id = :ownerId AND r.status = 'APPROVED') AS approved
            """, nativeQuery = true)
    DashboardSummaryRow summarize(@Param("ownerId") Long ownerId);

    // DASH-002 검토자 집계. 소유자가 아니라 검토 업무 전체가 대상이라 사용자 조건이 없다.
    // DASH-001 과 같은 이유로 스칼라 서브쿼리 하나로 묶어 왕복을 한 번으로 줄인다.
    // highFindings 는 "지금 검토가 걸려 있는 HIGH Finding" 이다.
    // 이미 결정된 검토의 Finding 까지 세면 카드가 줄지 않아 남은 일감을 나타내지 못한다.
    // decidedInRange 는 [fromAt, toAt) 반열림 구간이다. 경계는 서비스가 계산해 넘긴다.
    @Query(value = """
            SELECT (SELECT count(*) FROM reviews r
                     WHERE r.status = 'PENDING')                          AS pendingReviews,
                   (SELECT count(*) FROM findings f
                             JOIN reviews r ON r.analysis_id = f.analysis_id
                     WHERE f.severity = 'HIGH'
                       AND r.status = 'PENDING')                          AS highFindings,
                   (SELECT count(*) FROM risk_patterns rp
                     WHERE rp.status = 'ACTIVE')                          AS activeRiskPatterns,
                   (SELECT count(*) FROM reviews r
                     WHERE r.status IN ('APPROVED', 'REJECTED')
                       AND r.decided_at >= :fromAt
                       AND r.decided_at < :toAt)                          AS decidedInRange
            """, nativeQuery = true)
    ComplianceSummaryRow summarizeCompliance(@Param("fromAt") OffsetDateTime fromAt,
                                             @Param("toAt") OffsetDateTime toAt);
}
