package com.crosschecklab.domain.product.dto;

import com.crosschecklab.domain.product.Product;
import com.crosschecklab.global.common.enums.ProductType;
import java.time.OffsetDateTime;

// 목록 항목. 상세와 필드를 맞추되 description 은 뺀다 (목록 화면에서 쓰지 않는다).
public record ProductSummaryResponse(
        Long productId,
        String name,
        ProductType productType,
        Long ownerId,
        String ownerName,
        LatestDocumentResponse latestDocument,
        LatestAnalysisResponse latestAnalysis,
        OffsetDateTime createdAt
) {

    /**
     * Product 엔티티와 최신 문서·분석 정보를 조합해 목록 응답을 만든다.
     *
     * @param product 상품 엔티티
     * @param latestDocument 최신 문서 정보 (없으면 null)
     * @param latestAnalysis 최신 분석 정보 (없으면 null)
     * @return 상품 목록 항목 DTO
     */
    public static ProductSummaryResponse of(Product product, LatestDocumentResponse latestDocument,
                                            LatestAnalysisResponse latestAnalysis) {
        return new ProductSummaryResponse(
                product.getId(),
                product.getName(),
                product.getProductType(),
                product.getOwner().getId(),
                product.getOwner().getName(),
                latestDocument,
                latestAnalysis,
                product.getCreatedAt()
        );
    }
}
