package com.crosschecklab.domain.product;

import com.crosschecklab.domain.user.User;
import com.crosschecklab.global.common.BaseTimeEntity;
import com.crosschecklab.global.common.enums.ProductType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 검증 대상 금융상품. 문서·분석·검토가 모두 이 상품에 매달린다.
// ERD 에 상품 status 컬럼이 없다. 진행 상태는 최신 문서의 extract_status 와
// 최신 분석의 status 로 파생하므로 여기에 상태 필드를 만들지 않는다.
@Entity
@Getter
@Table(name = "products")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 소유자는 등록 시점의 요청 사용자로 고정되며 이후 변경하지 않는다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    private User owner;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false, length = 20)
    private ProductType productType;

    @Column(length = 500)
    private String description;

    private Product(User owner, String name, ProductType productType, String description) {
        this.owner = owner;
        this.name = name;
        this.productType = productType;
        this.description = description;
    }

    public static Product create(User owner, String name, ProductType productType, String description) {
        return new Product(owner, name, productType, description);
    }

    // 소유권 검증에 owner 프록시 초기화가 필요 없도록 id 만 꺼낸다.
    public Long getOwnerId() {
        return owner.getId();
    }
}
