package com.crosschecklab.domain.product;

import com.crosschecklab.domain.document.ProductDocument;
import com.crosschecklab.domain.document.ProductDocumentRepository;
import com.crosschecklab.domain.product.dto.LatestDocumentResponse;
import com.crosschecklab.domain.product.dto.ProductCreateRequest;
import com.crosschecklab.domain.product.dto.ProductResponse;
import com.crosschecklab.domain.product.dto.ProductSummaryResponse;
import com.crosschecklab.domain.user.User;
import com.crosschecklab.domain.user.UserRepository;
import com.crosschecklab.global.common.PageResponse;
import com.crosschecklab.global.common.enums.UserRole;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import com.crosschecklab.global.security.DemoUser;
import com.crosschecklab.global.security.OwnershipChecker;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductDocumentRepository productDocumentRepository;
    private final UserRepository userRepository;
    private final OwnershipChecker ownershipChecker;

    // PROD-001. 소유자는 요청 본문이 아니라 인증된 사용자로 정한다.
    @Transactional
    public ProductResponse create(ProductCreateRequest request, DemoUser currentUser) {
        ownershipChecker.requireRole(currentUser, UserRole.PRODUCT_MANAGER);

        // 인증 시점에 존재를 확인했지만, 연관관계에 넣을 영속 상태의 User 가 필요해 다시 읽는다.
        User owner = userRepository.findById(currentUser.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.DEMO_USER_NOT_FOUND));

        Product product = productRepository.save(
                Product.create(owner, request.name(), request.productType(), request.description()));

        // 방금 만든 상품이라 문서가 있을 수 없다.
        return ProductResponse.of(product, null);
    }

    // PROD-002. 소유자 본인 또는 검토자만 조회할 수 있다.
    public ProductResponse findById(Long productId, DemoUser currentUser) {
        Product product = productRepository.findWithOwnerById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        ownershipChecker.requireOwnerOrReviewer(product.getOwnerId(), currentUser);

        LatestDocumentResponse latestDocument = productDocumentRepository
                .findFirstByProduct_IdOrderByIdDesc(productId)
                .map(LatestDocumentResponse::from)
                .orElse(null);

        return ProductResponse.of(product, latestDocument);
    }

    // 목록. 담당자는 본인 상품만, 검토자는 전체를 본다.
    public PageResponse<ProductSummaryResponse> findPage(int page, int size, DemoUser currentUser) {
        Long ownerFilter = currentUser.isComplianceReviewer() ? null : currentUser.id();

        Page<Product> products = productRepository.findPage(
                ownerFilter, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));

        Map<Long, LatestDocumentResponse> latestDocuments = loadLatestDocuments(products.getContent());

        return PageResponse.of(products,
                product -> ProductSummaryResponse.of(product, latestDocuments.get(product.getId())));
    }

    // 상품마다 최신 문서를 조회하면 N+1 이 되므로 페이지 전체를 한 번에 읽는다.
    private Map<Long, LatestDocumentResponse> loadLatestDocuments(List<Product> products) {
        if (products.isEmpty()) {
            return Map.of();
        }
        List<Long> productIds = products.stream().map(Product::getId).toList();
        return productDocumentRepository.findLatestByProductIds(productIds).stream()
                .collect(Collectors.toMap(ProductDocument::getProductId, LatestDocumentResponse::from));
    }
}
