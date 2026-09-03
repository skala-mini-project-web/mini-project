package com.crosschecklab.domain.product;

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

    // 목록. ownerId 가 null 이면 전체(검토자), 값이 있으면 본인 상품만(담당자).
    // countQuery 를 따로 주지 않으면 fetch join 때문에 count 쿼리 생성이 실패한다.
    @Query(value = """
            select p from Product p join fetch p.owner
            where (:ownerId is null or p.owner.id = :ownerId)
            """,
            countQuery = """
                    select count(p) from Product p
                    where (:ownerId is null or p.owner.id = :ownerId)
                    """)
    Page<Product> findPage(@Param("ownerId") Long ownerId, Pageable pageable);
}
