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
// 파일 바이너리는 저장하지 않는다. storage_key 는 mock:// 참조이고 checksum 만 실제 값이다.
// 상태 전이는 UPLOADED → EXTRACTING → READY 또는 FAILED 이며,
// 조회(GET)는 어떤 경우에도 상태를 바꾸지 않는다.
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

    private ProductDocument(Product product, String fileName, String mediaType,
                            Long fileSize, String checksum, String storageKey) {
        this.product = product;
        this.fileName = fileName;
        this.mediaType = mediaType;
        this.fileSize = fileSize;
        this.checksum = checksum;
        this.storageKey = storageKey;
        this.extractStatus = ExtractStatus.UPLOADED;
        this.confirmed = false;
    }

    // DOC-001. 업로드 직후에는 항상 UPLOADED 이고 추출 텍스트가 없다.
    public static ProductDocument upload(Product product, String fileName, String mediaType,
                                         Long fileSize, String checksum, String storageKey) {
        return new ProductDocument(product, fileName, mediaType, fileSize, checksum, storageKey);
    }

    public void markExtracting() {
        this.extractStatus = ExtractStatus.EXTRACTING;
    }

    // 재추출로 텍스트가 덮어써지면 이전에 확인한 내용이 아니므로 확인 상태를 되돌린다.
    public void markReady(String extractedText) {
        this.extractedText = extractedText;
        this.extractStatus = ExtractStatus.READY;
        this.confirmed = false;
        this.confirmedBy = null;
        this.confirmedAt = null;
    }

    public void markFailed() {
        this.extractStatus = ExtractStatus.FAILED;
    }

    public boolean isExtractionFinished() {
        return extractStatus == ExtractStatus.READY || extractStatus == ExtractStatus.FAILED;
    }

    public Long getProductId() {
        return product.getId();
    }

    // 소유권 검증은 document → product → owner 로 파생한다.
    public Long getOwnerId() {
        return product.getOwnerId();
    }

    // 지연 로딩 프록시를 초기화하지 않고 식별자만 꺼낸다.
    public Long getConfirmedById() {
        return confirmedBy == null ? null : confirmedBy.getId();
    }
}
