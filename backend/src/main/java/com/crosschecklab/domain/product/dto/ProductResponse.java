package com.crosschecklab.domain.product.dto;

import com.crosschecklab.domain.product.Product;
import com.crosschecklab.global.common.enums.ProductType;
import java.time.OffsetDateTime;

// PROD-002 상세 응답. 목록(ProductSummaryResponse)과 필드를 맞춰 두어
// FE 가 목록에서 상세로 넘어갈 때 같은 모양을 기대할 수 있게 한다.
// latestDocument / latestAnalysis 는 아직 없을 수 있고, 이때는 키를 빼지 않고 null 로 내린다.
public record ProductResponse(
        Long productId,
        String name,
        ProductType productType,
        String description,
        Long ownerId,
        String ownerName,
        LatestDocumentResponse latestDocument,
        LatestAnalysisResponse latestAnalysis,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    /**
     * Product 엔티티와 최신 문서·분석 정보를 조합해 상세 응답을 만든다.
     *
     * @param product 상품 엔티티
     * @param latestDocument 최신 문서 정보 (없으면 null)
     * @param latestAnalysis 최신 분석 정보 (없으면 null)
     * @return 상품 상세 응답 DTO
     */
    public static ProductResponse of(Product product, LatestDocumentResponse latestDocument,
                                     LatestAnalysisResponse latestAnalysis) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getProductType(),
                product.getDescription(),
                product.getOwner().getId(),
                product.getOwner().getName(),
                latestDocument,
                latestAnalysis,
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
