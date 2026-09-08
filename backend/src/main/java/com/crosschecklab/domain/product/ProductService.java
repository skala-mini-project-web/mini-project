package com.crosschecklab.domain.product;

import com.crosschecklab.domain.analysis.AnalysisRepository;
import com.crosschecklab.domain.analysis.ProductLatestAnalysis;
import com.crosschecklab.domain.document.ProductDocument;
import com.crosschecklab.domain.document.ProductDocumentRepository;
import com.crosschecklab.domain.product.ProductRepository.ProductListRow;
import com.crosschecklab.domain.product.dto.LatestAnalysisResponse;
import com.crosschecklab.domain.product.dto.LatestDocumentResponse;
import com.crosschecklab.domain.product.dto.ProductCreateRequest;
import com.crosschecklab.domain.product.dto.ProductResponse;
import com.crosschecklab.domain.product.dto.ProductSummaryResponse;
import com.crosschecklab.domain.user.User;
import com.crosschecklab.domain.user.UserRepository;
import com.crosschecklab.global.common.PageResponse;
import com.crosschecklab.global.common.enums.AnalysisStatus;
import com.crosschecklab.global.common.enums.ProductLifecycleStatus;
import com.crosschecklab.global.common.enums.ProductType;
import com.crosschecklab.global.common.enums.UserRole;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import com.crosschecklab.global.security.DemoUser;
import com.crosschecklab.global.security.OwnershipChecker;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    // ECMAScript \s / trim 과 동일하다. Java 의 \s, strip, Unicode White_Space 와는 다르다.
    private static final String SEARCH_WHITESPACE =
            "\t\n\u000B\f\r \u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005"
                    + "\u2006\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF";
    private static final Pattern EDGE_WHITESPACE =
            Pattern.compile("\\A[" + SEARCH_WHITESPACE + "]+|[" + SEARCH_WHITESPACE + "]+\\z");
    private static final Pattern ALL_WHITESPACE = Pattern.compile("[" + SEARCH_WHITESPACE + "]");
    private static final String CHOSEONG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";

    private final ProductRepository productRepository;
    private final ProductDocumentRepository productDocumentRepository;
    private final AnalysisRepository analysisRepository;
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

        // 방금 만든 상품이라 문서도 분석도 있을 수 없다.
        return ProductResponse.of(product, null, null);
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

        // 상세의 기존 질의를 유지한다. 목록 SQL 도 같은 상품별 최대 분석 ID 규칙을 쓴다.
        LatestAnalysisResponse latestAnalysis = loadLatestAnalyses(List.of(productId)).get(productId);

        return ProductResponse.of(product, latestDocument, latestAnalysis);
    }

    // 목록. 담당자는 본인 상품만, 검토자는 전체를 본다.
    public PageResponse<ProductSummaryResponse> findPage(int page, int size, String q,
                                                        ProductType productType, ProductLifecycleStatus status,
                                                        DemoUser currentUser) {
        Long ownerFilter = currentUser.isComplianceReviewer() ? null : currentUser.id();
        String query = q == null ? "" : EDGE_WHITESPACE.matcher(q).replaceAll("").toLowerCase(Locale.ROOT);

        Page<ProductListRow> products = productRepository.findPage(
                ownerFilter, query.isEmpty() ? null : query, choseongRegex(query), SEARCH_WHITESPACE,
                productType == null ? null : productType.name(), status == null ? null : status.name(),
                PageRequest.of(page, size));

        List<Long> productIds = products.getContent().stream().map(ProductListRow::getProductId).toList();
        Map<Long, LatestDocumentResponse> latestDocuments = loadLatestDocuments(productIds);

        return PageResponse.of(products, product -> new ProductSummaryResponse(
                product.getProductId(), product.getName(), ProductType.valueOf(product.getProductType()),
                product.getOwnerId(), product.getOwnerName(), latestDocuments.get(product.getProductId()),
                product.getAnalysisId() == null ? null : new LatestAnalysisResponse(
                        product.getAnalysisId(), AnalysisStatus.valueOf(product.getAnalysisStatus())),
                ProductLifecycleStatus.valueOf(product.getStatus()),
                product.getCreatedAt().atOffset(ZoneOffset.UTC)));
    }

    // 사용자 입력을 정규식으로 실행하지 않는다. 19개 초성만 검증한 뒤 고정된 문자 범위를 만든다.
    private static String choseongRegex(String query) {
        String compact = ALL_WHITESPACE.matcher(query).replaceAll("");
        if (compact.isEmpty()) {
            return null;
        }
        StringBuilder regex = new StringBuilder(compact.length() * 6);
        for (int i = 0; i < compact.length(); i++) {
            char consonant = compact.charAt(i);
            int index = CHOSEONG.indexOf(consonant);
            if (index < 0) {
                return null;
            }
            char first = (char) (0xAC00 + index * 588);
            char last = (char) (first + 587);
            regex.append('[').append(consonant).append(first).append('-').append(last).append(']');
        }
        return regex.toString();
    }

    // 상품마다 최신 문서를 조회하면 N+1 이 되므로 페이지 전체를 한 번에 읽는다.
    private Map<Long, LatestDocumentResponse> loadLatestDocuments(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return productDocumentRepository.findLatestByProductIds(productIds).stream()
                .collect(Collectors.toMap(ProductDocument::getProductId, LatestDocumentResponse::from));
    }

    // 최신 분석도 같은 이유로 한 번에 읽는다. 분석 이력이 없는 상품은 결과에 아예 들어오지 않고,
    // 호출부의 map.get 이 null 을 돌려주어 응답에서 latestAnalysis 가 null 이 된다.
    private Map<Long, LatestAnalysisResponse> loadLatestAnalyses(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return analysisRepository.findLatestByProductIds(productIds).stream()
                .collect(Collectors.toMap(ProductLatestAnalysis::productId, LatestAnalysisResponse::from));
    }
}
