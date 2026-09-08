package com.crosschecklab.domain.document;

import com.crosschecklab.domain.analysis.AnalysisRepository;
import com.crosschecklab.domain.document.batch.DocumentBatchItemRepository;
import com.crosschecklab.domain.document.dto.DocumentAcceptedResponse;
import com.crosschecklab.domain.document.dto.DocumentResponse;
import com.crosschecklab.domain.document.dto.DocumentTextUpdateRequest;
import com.crosschecklab.domain.document.extraction.DocumentExtractionPage;
import com.crosschecklab.domain.document.extraction.ExtractionScenarioResolver;
import com.crosschecklab.domain.document.extraction.PdfRenderLimits;
import com.crosschecklab.domain.document.storage.FileStorage;
import com.crosschecklab.domain.document.storage.StoredFile;
import com.crosschecklab.domain.groundtruth.GroundTruthFactService;
import com.crosschecklab.domain.product.Product;
import com.crosschecklab.domain.product.ProductRepository;
import com.crosschecklab.domain.user.User;
import com.crosschecklab.domain.user.UserRepository;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import com.crosschecklab.global.error.ErrorResponse;
import com.crosschecklab.global.security.DemoUser;
import com.crosschecklab.global.security.OwnershipChecker;
import jakarta.persistence.EntityManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
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
    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    private final ProductRepository productRepository;
    private final ProductDocumentRepository productDocumentRepository;
    private final DocumentBatchItemRepository documentBatchItemRepository;
    private final DocumentSourceRevisionRepository documentSourceRevisionRepository;
    private final AnalysisRepository analysisRepository;
    private final GroundTruthFactService groundTruthFactService;
    private final UserRepository userRepository;
    private final ExtractionScenarioResolver scenarioResolver;
    private final FileStorage fileStorage;
    private final OwnershipChecker ownershipChecker;
    private final ApplicationEventPublisher eventPublisher;
    private final EntityManager entityManager;
    private final Clock clock;

    // DOC-001. 저장은 즉시, 추출은 커밋 이후 비동기로 진행된다.
    @Transactional
    public DocumentAcceptedResponse upload(Long productId, MultipartFile file,
                                           String requestedScenario, DemoUser currentUser) {
        Product product = productRepository.findWithOwnerById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        // 문서 업로드는 쓰기 작업이라 담당자 본인만 가능하다 (검토자도 올릴 수 없다).
        ownershipChecker.requireOwner(product.getOwnerId(), currentUser);

        ValidatedUpload upload = validateUpload(file, requestedScenario, "file");

        StoredFile stored = fileStorage.store(file, upload.scenarioCode());

        ProductDocument document = ProductDocument.upload(
                product, upload.fileName(), upload.mediaType().contentType(),
                stored.size(), stored.checksum(), stored.storageKey());
        String extractionToken = document.reserveExtraction(databaseNow().plusMinutes(5));
        productDocumentRepository.saveAndFlush(document);
        documentSourceRevisionRepository.save(DocumentSourceRevision.initial(document));

        eventPublisher.publishEvent(
                new DocumentExtractionRequestedEvent(document.getId(), extractionToken));

        return DocumentAcceptedResponse.from(document);
    }

    // DOC-002. 폴링용 조회. 어떤 상태도 변경하지 않는다.
    public DocumentResponse findById(Long documentId, DemoUser currentUser) {
        ProductDocument document = productDocumentRepository.findWithProductOwnerById(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        ownershipChecker.requireOwnerOrReviewer(document.getOwnerId(), currentUser);
        return response(document);
    }

    // DOC-003. 추출 텍스트 수정과 확인. READY 상태에서만 허용한다.
    @Transactional
    public DocumentResponse updateText(
            Long documentId,
            DocumentTextUpdateRequest request,
            Long expectedRunId,
            String expectedTextHash,
            DemoUser currentUser
    ) {
        ProductDocument document = getOwnedDocumentForUpdate(documentId, currentUser);
        if (analysisRepository.existsByProductDocumentId(documentId)) {
            throw new BusinessException(ErrorCode.DOCUMENT_ALREADY_ANALYZED);
        }
        if (!document.isReady()) {
            // 추출 중이거나 실패한 텍스트를 고치면 이후 추출 결과에 덮어써진다.
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_READY);
        }
        validateCurrentConfirmationTarget(document, request, expectedRunId, expectedTextHash);

        // confirmed_by 는 FK 라서 영속 상태의 User 가 필요하다. 해제 요청이면 조회하지 않는다.
        User editor = request.confirmed() ? loadCurrentUser(currentUser) : null;
        document.updateExtractedText(request.extractedText(), request.confirmed(),
                editor, OffsetDateTime.now(clock));
        if (request.confirmed()) {
            groundTruthFactService.refreshFromConfirmedDocument(document, editor);
        }

        return response(document);
    }

    public RenderArtifact findCurrentPageRender(Long documentId, int pageNumber, DemoUser currentUser) {
        ProductDocument document = productDocumentRepository.findWithProductOwnerById(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        ownershipChecker.requireOwnerOrReviewer(document.getOwnerId(), currentUser);
        if (document.getCurrentExtractionRunId() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        DocumentExtractionPage page = entityManager.createQuery("""
                        select p from DocumentExtractionPage p
                        where p.extractionRun.id = :runId and p.pageNumber = :pageNumber
                        """, DocumentExtractionPage.class)
                .setParameter("runId", document.getCurrentExtractionRunId())
                .setParameter("pageNumber", pageNumber)
                .getResultStream()
                .findFirst()
                .filter(value -> value.getOcrRenderArtifactKey() != null)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        byte[] source = fileStorage.read(document.getStorageKey())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        byte[] content = renderPage(source, page.getPageNumber(), page.getOcrConfigSnapshot());
        if (!Objects.equals(sha256(content), page.getOcrRenderArtifactHash())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return new RenderArtifact(content, page.getOcrRenderArtifactHash());
    }

    // DOC-004. 실패한 추출만 다시 돌린다.
    // 상태 확인과 전이 사이에 다른 요청이 끼어들면 같은 문서의 추출이 두 번 돌아가므로,
    // 상태를 읽기 전에 문서 행을 잠근다. (버튼 연타만으로도 재현된다)
    // 잠금 조회를 가장 먼저 해야 한다. 엔티티가 이미 영속성 컨텍스트에 있으면
    // 잠금을 걸어도 메모리에 있는 이전 상태가 그대로 쓰인다.
    @Transactional
    public DocumentAcceptedResponse retryExtraction(Long documentId, DemoUser currentUser) {
        ProductDocument document = productDocumentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        ownershipChecker.requireOwner(document.getOwnerId(), currentUser);

        // 먼저 통과한 요청이 이미 EXTRACTING 으로 옮겨 두었으므로 뒤이은 요청은 여기서 409 가 된다.
        // 배치 문서는 배치의 lease/fence 경로만 소유한다. 단건 실행으로 우회하지 않는다.
        if (!document.isRetryableFailure()
                || documentBatchItemRepository.existsByProductDocument_Id(documentId)) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_RETRYABLE);
        }

        OffsetDateTime extractionDeadline = databaseNow().plusMinutes(5);
        document.markExtracting();
        String extractionToken = document.reserveExtraction(extractionDeadline);
        eventPublisher.publishEvent(
                new DocumentExtractionRequestedEvent(documentId, extractionToken));

        return DocumentAcceptedResponse.from(document);
    }

    // 쓰기 작업용 조회. 담당자 본인이 아니면 403 이다 (검토자도 수정·재시도는 할 수 없다).
    private ProductDocument getOwnedDocumentForUpdate(Long documentId, DemoUser currentUser) {
        ProductDocument document = productDocumentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        ownershipChecker.requireOwner(document.getOwnerId(), currentUser);
        return document;
    }

    private User loadCurrentUser(DemoUser currentUser) {
        return userRepository.findById(currentUser.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.DEMO_USER_NOT_FOUND));
    }

    private OffsetDateTime databaseNow() {
        return (OffsetDateTime) entityManager
                .createNativeQuery("select clock_timestamp()", OffsetDateTime.class)
                .getSingleResult();
    }

    private DocumentResponse response(ProductDocument document) {
        Long runId = document.getCurrentExtractionRunId();
        if (runId == null) {
            return DocumentResponse.from(document);
        }
        List<DocumentExtractionPage> pages = entityManager.createQuery("""
                        select p from DocumentExtractionPage p
                        where p.extractionRun.id = :runId
                        order by p.pageNumber
                        """, DocumentExtractionPage.class)
                .setParameter("runId", runId)
                .getResultList();
        return DocumentResponse.from(document, pages);
    }

    private void validateCurrentConfirmationTarget(
            ProductDocument document,
            DocumentTextUpdateRequest request,
            Long expectedRunId,
            String expectedTextHash
    ) {
        if (!request.confirmed() || document.getCurrentExtractionRunId() == null) {
            return;
        }
        if (expectedRunId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, List.of(
                    new ErrorResponse.FieldError(
                            "expectedRunId",
                            "현재 추출 실행 ID가 필요합니다.")));
        }
        if (expectedTextHash == null || !SHA_256.matcher(expectedTextHash).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, List.of(
                    new ErrorResponse.FieldError(
                            "expectedTextHash",
                            "소문자 SHA-256 해시 형식이어야 합니다.")));
        }
        if (!Objects.equals(request.extractedText(), document.getExtractedText())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, List.of(
                    new ErrorResponse.FieldError(
                            "extractedText",
                            "수정한 텍스트를 먼저 저장한 뒤 확인하세요.")));
        }
        if (!Objects.equals(expectedRunId, document.getCurrentExtractionRunId())
                || !Objects.equals(expectedTextHash, document.getExtractedTextHash())) {
            throw new BusinessException(ErrorCode.DOCUMENT_CONFIRMATION_STALE);
        }
    }

    private byte[] renderPage(byte[] source, int pageNumber, java.util.Map<String, Object> config) {
        Object configuredDpi = config == null ? null : config.get("renderDpi");
        int dpi = configuredDpi instanceof Number value ? value.intValue() : 300;
        try (PDDocument pdf = Loader.loadPDF(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfRenderLimits.validate(pdf.getPage(pageNumber - 1), dpi);
            BufferedImage image = new PDFRenderer(pdf)
                    .renderImageWithDPI(pageNumber - 1, dpi, ImageType.RGB);
            if (!ImageIO.write(image, "png", output)) {
                throw new IOException("PNG writer is unavailable");
            }
            return output.toByteArray();
        } catch (PdfRenderLimits.PdfRenderLimitException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        } catch (IOException exception) {
            throw new UncheckedIOException("OCR 페이지 렌더를 읽지 못했습니다.", exception);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public ValidatedUpload validateUpload(
            MultipartFile file,
            String requestedScenario,
            String fieldName
    ) {
        String fileName = resolveFileName(file);
        DocumentMediaType mediaType = validate(file, fileName, fieldName);
        String scenarioCode = scenarioResolver.resolveCode(requestedScenario, fileName);
        return new ValidatedUpload(fileName, mediaType, scenarioCode);
    }

    private DocumentMediaType validate(MultipartFile file, String fileName, String fieldName) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    List.of(new ErrorResponse.FieldError(fieldName, "업로드할 파일이 비어 있습니다.")));
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

    public record ValidatedUpload(
            String fileName,
            DocumentMediaType mediaType,
            String scenarioCode
    ) {
    }

    public record RenderArtifact(byte[] content, String sha256) {
    }
}
