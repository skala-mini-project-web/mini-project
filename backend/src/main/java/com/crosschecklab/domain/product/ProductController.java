package com.crosschecklab.domain.product;

import com.crosschecklab.domain.product.dto.ProductCreateRequest;
import com.crosschecklab.domain.product.dto.ProductResponse;
import com.crosschecklab.domain.product.dto.ProductSummaryResponse;
import com.crosschecklab.global.common.PageResponse;
import com.crosschecklab.global.security.CurrentUser;
import com.crosschecklab.global.security.DemoUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Product", description = "금융상품")
@Validated
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping
    @Operation(summary = "PROD-001 상품 등록",
            description = "소유자는 요청 사용자로 지정된다. PRODUCT_MANAGER 만 등록할 수 있다.")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductCreateRequest request,
                                                  @CurrentUser DemoUser currentUser) {
        ProductResponse response = productService.create(request, currentUser);
        return ResponseEntity.created(URI.create("/api/products/" + response.productId())).body(response);
    }

    @GetMapping("/{productId}")
    @Operation(summary = "PROD-002 상품 상세",
            description = "소유자 본인 또는 COMPLIANCE_REVIEWER 만 조회할 수 있다.")
    public ResponseEntity<ProductResponse> findById(@PathVariable Long productId,
                                                    @CurrentUser DemoUser currentUser) {
        return ResponseEntity.ok(productService.findById(productId, currentUser));
    }

    @GetMapping
    @Operation(summary = "상품 목록",
            description = "담당자는 본인 상품만, 검토자는 전체를 조회한다. 최신 등록 순으로 정렬한다.")
    public ResponseEntity<PageResponse<ProductSummaryResponse>> findPage(
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page 는 0 이상이어야 합니다.") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size 는 1 이상이어야 합니다.")
            @Max(value = 100, message = "size 는 100 을 넘을 수 없습니다.") int size,
            @CurrentUser DemoUser currentUser) {
        return ResponseEntity.ok(productService.findPage(page, size, currentUser));
    }
}
