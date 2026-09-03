package com.crosschecklab.domain.document;

import com.crosschecklab.domain.document.dto.DocumentResponse;
import com.crosschecklab.domain.document.dto.DocumentUploadResponse;
import com.crosschecklab.domain.document.extraction.ExtractionScenarioResolver;
import com.crosschecklab.domain.document.storage.FileStorage;
import com.crosschecklab.domain.document.storage.StoredFile;
import com.crosschecklab.domain.product.Product;
import com.crosschecklab.domain.product.ProductRepository;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import com.crosschecklab.global.error.ErrorResponse;
import com.crosschecklab.global.security.DemoUser;
import com.crosschecklab.global.security.OwnershipChecker;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductDocumentService {

    // application.yml 의 spring.servlet.multipart.max-file-size 와 같은 값.
    // 실제 서버에서는 파서가 먼저 끊지만, 여기서도 검사해야 일관된 에러 본문을 돌려줄 수 있다.
    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    private static final String DEFAULT_FILE_NAME = "unknown";

    private final ProductRepository productRepository;
    private final ProductDocumentRepository productDocumentRepository;
    private final ExtractionScenarioResolver scenarioResolver;
    private final FileStorage fileStorage;
    private final OwnershipChecker ownershipChecker;
    private final ApplicationEventPublisher eventPublisher;

    // DOC-001. 저장은 즉시, 추출은 커밋 이후 비동기로 진행된다.
    @Transactional
    public DocumentUploadResponse upload(Long productId, MultipartFile file,
                                         String requestedScenario, DemoUser currentUser) {
        Product product = productRepository.findWithOwnerById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        // 문서 업로드는 쓰기 작업이라 담당자 본인만 가능하다 (검토자도 올릴 수 없다).
        ownershipChecker.requireOwner(product.getOwnerId(), currentUser);

        String fileName = resolveFileName(file);
        DocumentMediaType mediaType = validate(file, fileName);
        String scenarioCode = scenarioResolver.resolveCode(requestedScenario, fileName);

        // 바이너리는 여기서 소비되고 버려진다. 남는 것은 체크섬과 mock:// 포인터뿐이다.
        StoredFile stored = fileStorage.store(file, scenarioCode);

        ProductDocument document = productDocumentRepository.save(ProductDocument.upload(
                product, fileName, mediaType.contentType(),
                stored.size(), stored.checksum(), stored.storageKey()));

        eventPublisher.publishEvent(new DocumentUploadedEvent(document.getId()));

        return DocumentUploadResponse.from(document);
    }

    // DOC-002. 폴링용 조회. 어떤 상태도 변경하지 않는다.
    public DocumentResponse findById(Long documentId, DemoUser currentUser) {
        ProductDocument document = productDocumentRepository.findWithProductOwnerById(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        ownershipChecker.requireOwnerOrReviewer(document.getOwnerId(), currentUser);
        return DocumentResponse.from(document);
    }

    private DocumentMediaType validate(MultipartFile file, String fileName) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    List.of(new ErrorResponse.FieldError("file", "업로드할 파일이 비어 있습니다.")));
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
        return DocumentMediaType.resolve(file.getContentType(), fileName)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_FILE_TYPE));
    }

    // 클라이언트가 전체 경로를 보내는 경우가 있어 마지막 조각만 남긴다.
    private String resolveFileName(MultipartFile file) {
        String original = file == null ? null : file.getOriginalFilename();
        if (!StringUtils.hasText(original)) {
            return DEFAULT_FILE_NAME;
        }
        String cleaned = StringUtils.getFilename(StringUtils.cleanPath(original));
        return StringUtils.hasText(cleaned) ? cleaned : DEFAULT_FILE_NAME;
    }
}
