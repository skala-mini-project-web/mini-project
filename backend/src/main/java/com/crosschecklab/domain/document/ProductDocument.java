package com.crosschecklab.domain.document;

import com.crosschecklab.domain.product.Product;
import com.crosschecklab.domain.user.User;
import com.crosschecklab.global.common.BaseTimeEntity;
import com.crosschecklab.global.common.enums.ExtractStatus;
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
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 상품 설명서 원본 문서와 추출 결과.
// 이번 이슈(PROD-001/002)에서는 상세 응답의 latestDocument 를 채우기 위한 조회 전용이다.
// 업로드·추출·상태 전이(DOC-001~004)는 다음 이슈에서 이 엔티티에 붙인다.
// 파일 바이너리는 저장하지 않는다. storage_key 는 mock:// 참조이고 checksum 만 실제 값이다.
@Entity
@Getter
@Table(name = "product_documents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductDocument extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "media_type", nullable = false, length = 150)
    private String mediaType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(length = 64)
    private String checksum;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "extract_status", nullable = false, length = 20)
    private ExtractStatus extractStatus;

    @Column(name = "extracted_text", columnDefinition = "text")
    private String extractedText;

    @Column(nullable = false)
    private boolean confirmed;

    // 추출 텍스트를 확인한 사용자. 확인 전에는 null 이다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmed_by")
    private User confirmedBy;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    public Long getProductId() {
        return product.getId();
    }
}
