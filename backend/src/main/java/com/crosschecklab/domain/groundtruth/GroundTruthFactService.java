package com.crosschecklab.domain.groundtruth;

import com.crosschecklab.domain.document.ProductDocument;
import com.crosschecklab.domain.document.ProductDocumentRepository;
import com.crosschecklab.domain.groundtruth.GroundTruthFact.VerificationStatus;
import com.crosschecklab.domain.groundtruth.dto.GroundTruthFactListResponse;
import com.crosschecklab.domain.groundtruth.dto.GroundTruthFactResponse;
import com.crosschecklab.domain.groundtruth.dto.GroundTruthFactVerificationRequest;
import com.crosschecklab.domain.user.User;
import com.crosschecklab.domain.user.UserRepository;
import com.crosschecklab.global.common.enums.UserRole;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import com.crosschecklab.global.error.ErrorResponse;
import com.crosschecklab.global.security.DemoUser;
import com.crosschecklab.global.security.OwnershipChecker;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GroundTruthFactService {

    private static final String SNAPSHOT_LABEL = "확정 문서 텍스트";

    private final GroundTruthFactRepository groundTruthFactRepository;
    private final ProductDocumentRepository productDocumentRepository;
    private final UserRepository userRepository;
    private final OwnershipChecker ownershipChecker;
    private final EntityManager entityManager;
    private final Clock clock;

    /**
     * The only fact source is the text a product manager has explicitly confirmed.
     * Reconfirming the same text preserves its decision; changing the text replaces the
     * single snapshot and resets the entity to CANDIDATE.
     */
    @Transactional
    public void refreshFromConfirmedDocument(ProductDocument document, User confirmer) {
        // updateText changes the managed document immediately before this call. Flush that
        // change, then reload under the same row lock used by verification and analysis
        // acceptance so none of those workflows can observe or decide different versions.
        Long documentId = document.getId();
        entityManager.flush();
        entityManager.detach(document);
        ProductDocument confirmedDocument = productDocumentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));

        requireConfirmedDocument(confirmedDocument);
        String value = confirmedDocument.getExtractedText();
        String sourceTextSha256 = sha256(value);

        GroundTruthFact fact = groundTruthFactRepository.findByDocumentId(documentId)
                .orElse(null);
        if (fact == null) {
            groundTruthFactRepository.save(GroundTruthFact.create(
                    confirmedDocument, SNAPSHOT_LABEL, value, sourceTextSha256, confirmer));
            return;
        }
        if (!fact.getValue().equals(value)) {
            fact.replaceSnapshot(SNAPSHOT_LABEL, value, sourceTextSha256, confirmer);
        }
    }

    public GroundTruthFactListResponse list(Long documentId, DemoUser currentUser) {
        ownershipChecker.requireRole(currentUser, UserRole.PRODUCT_MANAGER);
        ProductDocument document = loadDocument(documentId);
        ownershipChecker.requireOwner(document.getOwnerId(), currentUser);
        requireConfirmedDocument(document);
        return GroundTruthFactListResponse.from(
                groundTruthFactRepository.findAllByDocumentIdOrderByIdAsc(documentId));
    }

    @Transactional
    public GroundTruthFactResponse verify(Long factId, GroundTruthFactVerificationRequest request,
                                          DemoUser currentUser) {
        ownershipChecker.requireRole(currentUser, UserRole.PRODUCT_MANAGER);
        GroundTruthFact factLocator = groundTruthFactRepository.findById(factId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Long documentId = factLocator.getDocumentId();
        entityManager.detach(factLocator);

        ProductDocument document = productDocumentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        ownershipChecker.requireOwner(document.getOwnerId(), currentUser);
        requireConfirmedDocument(document);

        // Confirmation uses the same document-row lock. Reload only after acquiring it so
        // a decision can never be applied to the snapshot observed before a reconfirmation.
        GroundTruthFact fact = groundTruthFactRepository.findById(factId)
                .filter(current -> current.getDocumentId().equals(documentId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (request.verificationStatus() != VerificationStatus.VERIFIED
                && request.verificationStatus() != VerificationStatus.REJECTED) {
            throw validationError("verificationStatus", "VERIFIED 또는 REJECTED 만 허용됩니다.");
        }
        if (!fact.getValue().equals(request.value())) {
            throw validationError("value", "현재 서버의 사실 값과 정확히 일치해야 합니다.");
        }
        // A stale snapshot must never be approved after the document changed outside this workflow.
        if (!document.getExtractedText().equals(fact.getValue())) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_CONFIRMED);
        }

        fact.decide(request.verificationStatus(), loadCurrentUser(currentUser), OffsetDateTime.now(clock));
        return GroundTruthFactResponse.from(fact);
    }

    private ProductDocument loadDocument(Long documentId) {
        return productDocumentRepository.findWithProductOwnerById(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
    }

    private User loadCurrentUser(DemoUser currentUser) {
        return userRepository.findById(currentUser.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.DEMO_USER_NOT_FOUND));
    }

    private void requireConfirmedDocument(ProductDocument document) {
        if (!document.isReady() || !document.isConfirmed()
                || document.getConfirmedBy() == null || !StringUtils.hasText(document.getExtractedText())) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_CONFIRMED);
        }
    }

    private BusinessException validationError(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR,
                List.of(new ErrorResponse.FieldError(field, message)));
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
