package com.crosschecklab.domain.product.dto;

import com.crosschecklab.global.common.enums.ProductType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// ownerId 를 받지 않는다. 소유자는 인증된 요청 사용자로 서버가 정한다.
public record ProductCreateRequest(
        @NotBlank(message = "상품명은 필수입니다.")
        @Size(min = 1, max = 100, message = "상품명은 1~100자여야 합니다.")
        String name,

        @NotNull(message = "상품 유형은 필수입니다.")
        ProductType productType,

        @Size(max = 500, message = "설명은 500자를 넘을 수 없습니다.")
        String description
) {
}
