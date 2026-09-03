package com.crosschecklab.domain.dashboard;

import com.crosschecklab.domain.product.Product;
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
}
