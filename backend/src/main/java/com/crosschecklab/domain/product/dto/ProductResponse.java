package com.crosschecklab.domain.product.dto;

import com.crosschecklab.domain.product.Product;
import com.crosschecklab.global.common.enums.ProductType;
import java.time.OffsetDateTime;

// PROD-002 상세 응답. 목록(ProductSummaryResponse)과 필드를 맞춰 두어
// FE 가 목록에서 상세로 넘어갈 때 같은 모양을 기대할 수 있게 한다.
//
// latestAnalysis 는 트랙 B 의 analyses 테이블에 의존한다.
// FE 재작업을 막기 위해 필드는 계약대로 두고 지금은 항상 null 을 내린다. B 머지 후 채운다.
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

    public static ProductResponse of(Product product, LatestDocumentResponse latestDocument) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getProductType(),
                product.getDescription(),
                product.getOwner().getId(),
                product.getOwner().getName(),
                latestDocument,
                null,
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
