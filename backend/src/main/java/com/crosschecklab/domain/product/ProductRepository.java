package com.crosschecklab.domain.product;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    // owner 는 응답의 ownerName 에 항상 필요하므로 fetch join 으로 함께 읽는다.
    @Query("select p from Product p join fetch p.owner where p.id = :id")
    Optional<Product> findWithOwnerById(@Param("id") Long id);

    // 내용과 count 가 소유권·검색·최신 분석 판정·상태 필터를 반드시 공유한다.
    String FILTERED_PRODUCTS = """
            WITH scoped AS (
                SELECT p.id, p.name, p.product_type, p.owner_id, u.name AS owner_name,
                       p.created_at AT TIME ZONE 'UTC' AS created_at,
                       latest.id AS analysis_id, latest.status AS analysis_status,
                       r.status AS review_status
                FROM products p
                JOIN users u ON u.id = p.owner_id
                LEFT JOIN LATERAL (
                    SELECT a.id, a.status
                    FROM analyses a
                    JOIN product_documents d ON d.id = a.product_document_id
                    WHERE d.product_id = p.id
                    ORDER BY a.id DESC
                    LIMIT 1
                ) latest ON TRUE
                LEFT JOIN reviews r ON r.analysis_id = latest.id
                WHERE (CAST(:ownerId AS bigint) IS NULL OR p.owner_id = CAST(:ownerId AS bigint))
                  AND (CAST(:productType AS text) IS NULL OR p.product_type = CAST(:productType AS text))
                  AND (CAST(:q AS text) IS NULL
                       OR strpos(lower(p.name), CAST(:q AS text)) > 0
                       OR strpos(CAST(p.id AS text), CAST(:q AS text)) > 0
                       OR (CAST(:choseongRegex AS text) IS NOT NULL
                           AND translate(p.name, CAST(:searchWhitespace AS text), '') COLLATE "C"
                               ~ CAST(:choseongRegex AS text) COLLATE "C"))
            ), classified AS (
                SELECT scoped.*,
                       CASE
                           WHEN analysis_id IS NULL THEN 'DRAFT'
                           WHEN analysis_status IN ('CREATED', 'RUNNING') THEN 'RUNNING'
                           WHEN review_status = 'APPROVED' THEN 'APPROVED'
                           WHEN review_status = 'REJECTED' THEN 'NEEDS_FIX'
                           WHEN review_status = 'PENDING' OR analysis_status = 'IN_REVIEW' THEN 'IN_REVIEW'
                           WHEN analysis_status = 'COMPLETED' THEN 'ANALYZED'
                           WHEN analysis_status = 'FAILED' THEN 'NEEDS_FIX'
                       END AS lifecycle_status
                FROM scoped
            )
            """;

    String STATUS_PREDICATE = """
            FROM classified
            WHERE (CAST(:status AS text) IS NULL OR lifecycle_status = CAST(:status AS text))
            """;

    @Query(value = FILTERED_PRODUCTS + """
            SELECT id AS "productId", name AS "name", product_type AS "productType",
                   owner_id AS "ownerId", owner_name AS "ownerName", created_at AS "createdAt",
                   analysis_id AS "analysisId", analysis_status AS "analysisStatus",
                   lifecycle_status AS "status"
            """ + STATUS_PREDICATE + " ORDER BY id DESC",
            countQuery = FILTERED_PRODUCTS + " SELECT count(*) " + STATUS_PREDICATE,
            nativeQuery = true)
    Page<ProductListRow> findPage(@Param("ownerId") Long ownerId,
                                  @Param("q") String q,
                                  @Param("choseongRegex") String choseongRegex,
                                  @Param("searchWhitespace") String searchWhitespace,
                                  @Param("productType") String productType,
                                  @Param("status") String status,
                                  Pageable pageable);

    interface ProductListRow {

        Long getProductId();

        String getName();

        String getProductType();

        Long getOwnerId();

        String getOwnerName();

        // SQL 에서 UTC 로 고정하여 native timestamp 의 시간대 추론에 의존하지 않는다.
        LocalDateTime getCreatedAt();

        Long getAnalysisId();

        String getAnalysisStatus();

        String getStatus();
    }
}
