package com.crosschecklab.domain.document;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductDocumentRepository extends JpaRepository<ProductDocument, Long> {

    // 상품 상세의 latestDocument. id 가 큰 쪽이 최신이다 (identity 증가).
    // 엔티티에 getProductId() 헬퍼가 있어 productId 를 프로퍼티로 오인하므로
    // 밑줄로 연관관계 탐색 경로(product.id)를 명시한다.
    Optional<ProductDocument> findFirstByProduct_IdOrderByIdDesc(Long productId);

    // 재시도처럼 현재 상태를 보고 다음 상태를 정하는 경로용. 문서 행에 쓰기 잠금을 건다.
    // 잠금 없이 읽으면 동시 요청이 둘 다 같은 상태를 보고 통과한다.
    // 이미 영속성 컨텍스트에 올라온 엔티티는 잠금을 걸어도 메모리 값이 갱신되지 않으므로
    // 반드시 다른 조회보다 먼저 호출해야 한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ProductDocument d where d.id = :id")
    Optional<ProductDocument> findByIdForUpdate(@Param("id") Long id);

    // 문서 상세. 소유권 검증에 product.owner 까지 필요하므로 함께 읽는다.
    @Query("select d from ProductDocument d join fetch d.product p join fetch p.owner where d.id = :id")
    Optional<ProductDocument> findWithProductOwnerById(@Param("id") Long id);

    // 목록 응답용. 상품마다 조회하면 N+1 이 되므로 한 페이지분을 한 번에 가져온다.
    @Query("""
            select d from ProductDocument d
            where d.product.id in :productIds
              and d.id = (select max(d2.id) from ProductDocument d2 where d2.product.id = d.product.id)
            """)
    List<ProductDocument> findLatestByProductIds(@Param("productIds") Collection<Long> productIds);
}
