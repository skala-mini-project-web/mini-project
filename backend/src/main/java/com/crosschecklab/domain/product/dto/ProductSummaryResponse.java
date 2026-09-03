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

    public static ProductSummaryResponse of(Product product, LatestDocumentResponse latestDocument) {
        return new ProductSummaryResponse(
                product.getId(),
                product.getName(),
                product.getProductType(),
                product.getOwner().getId(),
                product.getOwner().getName(),
                latestDocument,
                null,
                product.getCreatedAt()
        );
    }
}
