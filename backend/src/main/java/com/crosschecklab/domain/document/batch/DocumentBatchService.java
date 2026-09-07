package com.crosschecklab.domain.document.batch;

import com.crosschecklab.domain.document.DocumentSourceRevision;
import com.crosschecklab.domain.document.DocumentSourceRevisionRepository;
import com.crosschecklab.domain.document.ProductDocument;
import com.crosschecklab.domain.document.ProductDocumentRepository;
import com.crosschecklab.domain.document.ProductDocumentService;
import com.crosschecklab.domain.document.ProductDocumentService.ValidatedUpload;
import com.crosschecklab.domain.document.batch.dto.CreateDocumentBatchResponse;
import com.crosschecklab.domain.document.batch.dto.DocumentBatchItemPageResponse;
import com.crosschecklab.domain.document.batch.dto.DocumentBatchItemResponse;
import com.crosschecklab.domain.document.batch.dto.DocumentBatchResponse;
import com.crosschecklab.domain.document.storage.FileStorage;
import com.crosschecklab.domain.document.storage.StoredFile;
import com.crosschecklab.domain.product.Product;
import com.crosschecklab.domain.product.ProductRepository;
import com.crosschecklab.domain.user.User;
import com.crosschecklab.global.common.enums.UserRole;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import com.crosschecklab.global.error.ErrorResponse;
import com.crosschecklab.global.security.DemoUser;
import com.crosschecklab.global.security.OwnershipChecker;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class DocumentBatchService {

    private static final int MIN_FILE_COUNT = 1;
    private static final int MAX_FILE_COUNT = 100;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;
    private static final int MAX_ATTEMPTS = 3;
    private static final int DIGEST_BUFFER_SIZE = 8192;

    private final ProductRepository productRepository;
    private final ProductDocumentRepository productDocumentRepository;
    private final DocumentSourceRevisionRepository documentSourceRevisionRepository;
    private final DocumentBatchRepository documentBatchRepository;
    private final DocumentBatchItemRepository documentBatchItemRepository;
    private final ProductDocumentService productDocumentService;
    private final FileStorage fileStorage;
    private final OwnershipChecker ownershipChecker;
    private final EntityManager entityManager;
    private final Clock clock;

    @Transactional
    public CreateDocumentBatchResponse submit(
            Long productId,
            List<MultipartFile> files,
            String requestedScenario,
            String requestedIdempotencyKey,
            DemoUser currentUser
    ) {
        validateFileCount(files);
        String idempotencyKey = resolveIdempotencyKey(requestedIdempotencyKey);

        Product product = productRepository.findWithOwnerById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        ownershipChecker.requireOwner(product.getOwnerId(), currentUser);

        User owner = entityManager.find(User.class, currentUser.id(), LockModeType.PESSIMISTIC_WRITE);
        if (owner == null) {
            throw new BusinessException(ErrorCode.DEMO_USER_NOT_FOUND);
        }

        List<PreparedFile> preparedFiles = prepareFiles(files, requestedScenario);
        String scenario = preparedFiles.getFirst().validation().scenarioCode();
        String manifestHash = manifestHash(productId, scenario, preparedFiles);

        var existing = documentBatchRepository
                .findByOwner_IdAndIdempotencyKey(owner.getId(), idempotencyKey);
        if (existing.isPresent()) {
            DocumentBatch batch = existing.get();
            if (!batch.getManifestHash().equals(manifestHash)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return CreateDocumentBatchResponse.from(
                    DocumentBatchResponse.from(batch), true);
        }

        List<StoredFile> storedFiles = storeAll(preparedFiles);

        DocumentBatch batch = documentBatchRepository.save(DocumentBatch.create(
                product,
                owner,
                scenario,
                preparedFiles.size(),
                idempotencyKey,
                manifestHash));
        OffsetDateTime dueAt = OffsetDateTime.now(clock);

        for (int index = 0; index < preparedFiles.size(); index++) {
            PreparedFile prepared = preparedFiles.get(index);
            StoredFile stored = storedFiles.get(index);
            if (!prepared.checksum().equals(stored.checksum())
                    || prepared.file().getSize() != stored.size()) {
                throw new IllegalStateException("저장된 파일이 업로드 manifest 와 일치하지 않습니다.");
            }

            ProductDocument document = productDocumentRepository.save(ProductDocument.upload(
                    product,
                    prepared.validation().fileName(),
                    prepared.validation().mediaType().contentType(),
                    stored.size(),
                    stored.checksum(),
                    stored.storageKey()));
            documentSourceRevisionRepository.save(DocumentSourceRevision.initial(document));
            documentBatchItemRepository.save(DocumentBatchItem.create(
                    batch, document, index + 1, dueAt, MAX_ATTEMPTS));
        }

        entityManager.flush();
        return CreateDocumentBatchResponse.from(
                DocumentBatchResponse.from(batch), false);
    }

    @Transactional(readOnly = true)
    public DocumentBatchResponse findBatch(Long batchId, DemoUser currentUser) {
        DocumentBatch batch = requireBatch(batchId);
        ownershipChecker.requireOwnerOrReviewer(batch.getOwnerId(), currentUser);
        return batchResponse(batch);
    }

    @Transactional(readOnly = true)
    public DocumentBatchItemPageResponse findItems(
            Long batchId,
            int page,
            int size,
            DemoUser currentUser
    ) {
        DocumentBatch batch = requireBatch(batchId);
        ownershipChecker.requireOwnerOrReviewer(batch.getOwnerId(), currentUser);
        validatePage(page, size);

        List<DocumentBatchItem> items = entityManager.createQuery("""
                        select i from DocumentBatchItem i
                        where i.batch.id = :batchId
                        order by i.ordinal
                        """, DocumentBatchItem.class)
                .setParameter("batchId", batchId)
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList();
        long total = entityManager.createQuery("""
                        select count(i) from DocumentBatchItem i
                        where i.batch.id = :batchId
                        """, Long.class)
                .setParameter("batchId", batchId)
                .getSingleResult();
        List<DocumentBatchItemResponse> responses = items.stream()
                .map(item -> DocumentBatchItemResponse.from(item, latestAttempt(item.getId())))
                .toList();
        int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new DocumentBatchItemPageResponse(
                batchId, responses, page, size, total, totalPages);
    }

    @Transactional(readOnly = true)
    public DocumentBatchItemResponse findItem(
            Long batchId,
            Long itemId,
            DemoUser currentUser
    ) {
        DocumentBatch batch = requireBatch(batchId);
        ownershipChecker.requireOwnerOrReviewer(batch.getOwnerId(), currentUser);
        return itemResponse(requireItem(batchId, itemId));
    }

    @Transactional
    public DocumentBatchResponse cancelBatch(
            Long batchId,
            String requestedReason,
            DemoUser currentUser
    ) {
        String reason = validateReason(requestedReason);
        DocumentBatch batch = requireBatchForUpdate(batchId);
        requireManagerOwner(batch, currentUser);
        DocumentBatchStatus currentStatus = derivedStatus(batchId);
        if (currentStatus == DocumentBatchStatus.CANCELLED) {
            return batchResponse(batch);
        }
        if (currentStatus != DocumentBatchStatus.PENDING) {
            throw conflict();
        }

        List<DocumentBatchItem> items = lockItems(batchId);
        if (items.stream().anyMatch(item -> item.getStatus() == DocumentBatchItemStatus.LEASED)) {
            throw conflict();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (DocumentBatchItem item : items) {
            if (!item.getStatus().isTerminal()) {
                item.cancel(now, reason);
            } else if (item.getStatus() != DocumentBatchItemStatus.CANCELLED) {
                throw conflict();
            }
        }
        if (batch.getStatus() == DocumentBatchStatus.PENDING) {
            batch.cancel(now, reason);
        }
        entityManager.flush();
        return batchResponse(batch);
    }

    @Transactional
    public DocumentBatchResponse quarantineBatch(
            Long batchId,
            String requestedReason,
            DemoUser currentUser
    ) {
        String reason = validateReason(requestedReason);
        DocumentBatch batch = requireBatchForUpdate(batchId);
        requireManagerOwner(batch, currentUser);
        DocumentBatchStatus currentStatus = derivedStatus(batchId);
        if (currentStatus == DocumentBatchStatus.QUARANTINED) {
            return batchResponse(batch);
        }
        if (currentStatus != DocumentBatchStatus.PENDING) {
            throw conflict();
        }
        List<DocumentBatchItem> items = lockItems(batchId);
        if (items.stream().anyMatch(item -> item.getStatus() == DocumentBatchItemStatus.LEASED)) {
            throw conflict();
        }
        if (items.stream().anyMatch(item -> item.getStatus().isTerminal())) {
            throw conflict();
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        int updated = entityManager.createQuery("""
                        update DocumentBatchItem i
                        set i.status = :status,
                            i.terminalAt = :now,
                            i.quarantinedAt = :now,
                            i.quarantineReason = :reason,
                            i.updatedAt = :now
                        where i.batch.id = :batchId
                          and i.status in (:pending, :retryWaiting)
                        """)
                .setParameter("status", DocumentBatchItemStatus.QUARANTINED)
                .setParameter("now", now)
                .setParameter("reason", reason)
                .setParameter("batchId", batchId)
                .setParameter("pending", DocumentBatchItemStatus.PENDING)
                .setParameter("retryWaiting", DocumentBatchItemStatus.RETRY_WAIT)
                .executeUpdate();
        if (updated != items.size()) {
            throw conflict();
        }
        batch.quarantine(now, reason);
        entityManager.flush();
        entityManager.clear();
        return batchResponse(requireBatch(batchId));
    }

    @Transactional
    public DocumentBatchItemResponse cancelItem(
            Long batchId,
            Long itemId,
            String requestedReason,
            DemoUser currentUser
    ) {
        String reason = validateReason(requestedReason);
        DocumentBatch batch = requireBatchForUpdate(batchId);
        requireManagerOwner(batch, currentUser);
        DocumentBatchItem item = requireItemForUpdate(batchId, itemId);
        if (item.getStatus() == DocumentBatchItemStatus.CANCELLED) {
            return itemResponse(item);
        }
        if (item.getStatus() == DocumentBatchItemStatus.LEASED || item.getStatus().isTerminal()) {
            throw conflict();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        item.cancel(now, reason);
        entityManager.flush();
        finishBatchIfTerminal(batch, now, reason);
        return itemResponse(item);
    }

    @Transactional
    public DocumentBatchItemResponse quarantineItem(
            Long batchId,
            Long itemId,
            String requestedReason,
            DemoUser currentUser
    ) {
        String reason = validateReason(requestedReason);
        DocumentBatch batch = requireBatchForUpdate(batchId);
        requireManagerOwner(batch, currentUser);
        DocumentBatchItem item = requireItemForUpdate(batchId, itemId);
        if (item.getStatus() == DocumentBatchItemStatus.QUARANTINED) {
            return itemResponse(item);
        }
        if (item.getStatus() == DocumentBatchItemStatus.LEASED || item.getStatus().isTerminal()) {
            throw conflict();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        int updated = entityManager.createQuery("""
                        update DocumentBatchItem i
                        set i.status = :status,
                            i.terminalAt = :now,
                            i.quarantinedAt = :now,
                            i.quarantineReason = :reason,
                            i.updatedAt = :now
                        where i.id = :itemId
                          and i.status in (:pending, :retryWaiting)
                        """)
                .setParameter("status", DocumentBatchItemStatus.QUARANTINED)
                .setParameter("now", now)
                .setParameter("reason", reason)
                .setParameter("itemId", itemId)
                .setParameter("pending", DocumentBatchItemStatus.PENDING)
                .setParameter("retryWaiting", DocumentBatchItemStatus.RETRY_WAIT)
                .executeUpdate();
        if (updated != 1) {
            throw conflict();
        }
        entityManager.clear();
        batch = requireBatchForUpdate(batchId);
        finishBatchIfTerminal(batch, now, reason);
        return itemResponse(requireItem(batchId, itemId));
    }

    @Transactional
    public DocumentBatchItemResponse retryItem(
            Long batchId,
            Long itemId,
            DemoUser currentUser
    ) {
        DocumentBatch batch = requireBatchForUpdate(batchId);
        requireManagerOwner(batch, currentUser);
        DocumentBatchItem item = requireItemForUpdate(batchId, itemId);
        if (item.getStatus() == DocumentBatchItemStatus.PENDING
                || item.getStatus() == DocumentBatchItemStatus.RETRY_WAIT) {
            return itemResponse(item);
        }
        if (item.getStatus() != DocumentBatchItemStatus.QUARANTINED
                && item.getStatus() != DocumentBatchItemStatus.CANCELLED) {
            throw conflict();
        }
        if (item.getAttemptCount() >= 100) {
            throw conflict();
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        int updated = entityManager.createQuery("""
                        update DocumentBatchItem i
                        set i.status = :pending,
                            i.dueAt = :now,
                            i.maxAttempts = case when i.attemptCount >= i.maxAttempts
                                then i.maxAttempts + 1 else i.maxAttempts end,
                            i.cancelRequestedAt = null,
                            i.cancellationReason = null,
                            i.cancelledAt = null,
                            i.quarantinedAt = null,
                            i.quarantineReason = null,
                            i.terminalAt = null,
                            i.updatedAt = :now
                        where i.id = :itemId
                          and i.status in (:cancelled, :quarantined)
                        """)
                .setParameter("pending", DocumentBatchItemStatus.PENDING)
                .setParameter("now", now)
                .setParameter("itemId", itemId)
                .setParameter("cancelled", DocumentBatchItemStatus.CANCELLED)
                .setParameter("quarantined", DocumentBatchItemStatus.QUARANTINED)
                .executeUpdate();
        if (updated != 1) {
            throw conflict();
        }
        reopenBatch(batchId, now);
        entityManager.clear();
        return itemResponse(requireItem(batchId, itemId));
    }

    @Transactional(readOnly = true)
    public byte[] errorReport(Long batchId, DemoUser currentUser) {
        DocumentBatch batch = requireBatch(batchId);
        ownershipChecker.requireOwnerOrReviewer(batch.getOwnerId(), currentUser);
        List<DocumentBatchItem> items = entityManager.createQuery("""
                        select i from DocumentBatchItem i
                        where i.batch.id = :batchId
                        order by i.ordinal
                        """, DocumentBatchItem.class)
                .setParameter("batchId", batchId)
                .getResultList();
        StringBuilder csv = new StringBuilder(
                "batchId,ordinal,filename,state,attempts,errorCode,errorMessage,timestamp\r\n");
        for (DocumentBatchItem item : items) {
            DocumentBatchItemResponse response = itemResponse(item);
            csv.append(batchId).append(',')
                    .append(item.getOrdinal()).append(',')
                    .append(csvText(item.getSourceFileName())).append(',')
                    .append(csvText(item.getStatus().name())).append(',')
                    .append(item.getAttemptCount()).append(',')
                    .append(csvText(response.errorCode())).append(',')
                    .append(csvText(response.errorMessage())).append(',')
                    .append(csvText(response.errorTimestamp() == null
                            ? null : response.errorTimestamp().toString()))
                    .append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private DocumentBatch requireBatch(Long batchId) {
        DocumentBatch batch = entityManager.find(DocumentBatch.class, batchId);
        if (batch == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return batch;
    }

    private DocumentBatch requireBatchForUpdate(Long batchId) {
        DocumentBatch batch = entityManager.find(
                DocumentBatch.class, batchId, LockModeType.PESSIMISTIC_WRITE);
        if (batch == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return batch;
    }

    private DocumentBatchItem requireItem(Long batchId, Long itemId) {
        DocumentBatchItem item = entityManager.find(DocumentBatchItem.class, itemId);
        if (item == null || !item.getBatchId().equals(batchId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return item;
    }

    private DocumentBatchItem requireItemForUpdate(Long batchId, Long itemId) {
        DocumentBatchItem item = entityManager.find(
                DocumentBatchItem.class, itemId, LockModeType.PESSIMISTIC_WRITE);
        if (item == null || !item.getBatchId().equals(batchId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return item;
    }

    private List<DocumentBatchItem> lockItems(Long batchId) {
        return entityManager.createQuery("""
                        select i from DocumentBatchItem i
                        where i.batch.id = :batchId
                        order by i.ordinal
                        """, DocumentBatchItem.class)
                .setParameter("batchId", batchId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList();
    }

    private void requireManagerOwner(DocumentBatch batch, DemoUser currentUser) {
        ownershipChecker.requireRole(currentUser, UserRole.PRODUCT_MANAGER);
        ownershipChecker.requireOwner(batch.getOwnerId(), currentUser);
    }

    private DocumentBatchResponse batchResponse(DocumentBatch batch) {
        Map<DocumentBatchItemStatus, Long> counts = itemCounts(batch.getId());
        DocumentBatchStatus status = derivedStatus(counts);
        long attempts = entityManager.createQuery("""
                        select count(a) from DocumentBatchItemAttempt a
                        where a.item.batch.id = :batchId
                        """, Long.class)
                .setParameter("batchId", batch.getId())
                .getSingleResult();
        OffsetDateTime terminalAt = status.isTerminal()
                ? entityManager.createQuery("""
                                select max(i.terminalAt) from DocumentBatchItem i
                                where i.batch.id = :batchId
                                """, OffsetDateTime.class)
                        .setParameter("batchId", batch.getId())
                        .getSingleResult()
                : null;
        String cancellationReason = status == DocumentBatchStatus.CANCELLED
                ? latestItemReason(batch.getId(), DocumentBatchItemStatus.CANCELLED, false)
                : null;
        String quarantineReason = status == DocumentBatchStatus.QUARANTINED
                ? latestItemReason(batch.getId(), DocumentBatchItemStatus.QUARANTINED, true)
                : null;
        return DocumentBatchResponse.from(
                batch,
                status,
                counts.get(DocumentBatchItemStatus.PENDING),
                counts.get(DocumentBatchItemStatus.LEASED),
                counts.get(DocumentBatchItemStatus.RETRY_WAIT),
                counts.get(DocumentBatchItemStatus.SUCCEEDED),
                counts.get(DocumentBatchItemStatus.CANCELLED),
                counts.get(DocumentBatchItemStatus.QUARANTINED),
                attempts,
                cancellationReason,
                quarantineReason,
                terminalAt);
    }

    private String latestItemReason(
            Long batchId,
            DocumentBatchItemStatus status,
            boolean quarantine
    ) {
        String field = quarantine ? "i.quarantineReason" : "i.cancellationReason";
        List<String> reasons = entityManager.createQuery("""
                        select %s from DocumentBatchItem i
                        where i.batch.id = :batchId and i.status = :status
                        order by i.terminalAt desc, i.ordinal
                        """.formatted(field), String.class)
                .setParameter("batchId", batchId)
                .setParameter("status", status)
                .setMaxResults(1)
                .getResultList();
        return reasons.isEmpty() ? null : reasons.getFirst();
    }

    private Map<DocumentBatchItemStatus, Long> itemCounts(Long batchId) {
        Map<DocumentBatchItemStatus, Long> counts = new EnumMap<>(DocumentBatchItemStatus.class);
        for (DocumentBatchItemStatus status : DocumentBatchItemStatus.values()) {
            counts.put(status, 0L);
        }
        List<Object[]> rows = entityManager.createQuery("""
                        select i.status, count(i) from DocumentBatchItem i
                        where i.batch.id = :batchId
                        group by i.status
                        """, Object[].class)
                .setParameter("batchId", batchId)
                .getResultList();
        for (Object[] row : rows) {
            counts.put((DocumentBatchItemStatus) row[0], (Long) row[1]);
        }
        return counts;
    }

    private DocumentBatchStatus derivedStatus(Long batchId) {
        return derivedStatus(itemCounts(batchId));
    }

    private DocumentBatchStatus derivedStatus(Map<DocumentBatchItemStatus, Long> counts) {
        long active = counts.get(DocumentBatchItemStatus.PENDING)
                + counts.get(DocumentBatchItemStatus.LEASED)
                + counts.get(DocumentBatchItemStatus.RETRY_WAIT);
        if (active > 0) {
            return DocumentBatchStatus.PENDING;
        }
        if (counts.get(DocumentBatchItemStatus.QUARANTINED) > 0) {
            return DocumentBatchStatus.QUARANTINED;
        }
        if (counts.get(DocumentBatchItemStatus.CANCELLED) > 0) {
            return DocumentBatchStatus.CANCELLED;
        }
        return DocumentBatchStatus.SUCCEEDED;
    }

    private void finishBatchIfTerminal(
            DocumentBatch batch,
            OffsetDateTime now,
            String managementReason
    ) {
        entityManager.flush();
        DocumentBatchStatus status = derivedStatus(batch.getId());
        if (status == DocumentBatchStatus.PENDING || batch.getStatus().isTerminal()) {
            return;
        }
        if (status == DocumentBatchStatus.QUARANTINED) {
            batch.quarantine(now, managementReason);
        } else if (status == DocumentBatchStatus.CANCELLED) {
            batch.cancel(now, managementReason);
        } else {
            batch.markSucceeded(now);
        }
        entityManager.flush();
    }

    private void reopenBatch(Long batchId, OffsetDateTime now) {
        entityManager.createQuery("""
                        update DocumentBatch b
                        set b.status = :pending,
                            b.cancelRequestedAt = null,
                            b.cancellationReason = null,
                            b.cancelledAt = null,
                            b.quarantineReason = null,
                            b.quarantinedAt = null,
                            b.terminalAt = null,
                            b.updatedAt = :now
                        where b.id = :batchId
                        """)
                .setParameter("pending", DocumentBatchStatus.PENDING)
                .setParameter("now", now)
                .setParameter("batchId", batchId)
                .executeUpdate();
    }

    private DocumentBatchItemAttempt latestAttempt(Long itemId) {
        return entityManager.createQuery("""
                        select a from DocumentBatchItemAttempt a
                        where a.item.id = :itemId
                        order by a.attemptNo desc
                        """, DocumentBatchItemAttempt.class)
                .setParameter("itemId", itemId)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    private DocumentBatchItemResponse itemResponse(DocumentBatchItem item) {
        return DocumentBatchItemResponse.from(item, latestAttempt(item.getId()));
    }

    private String validateReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    List.of(new ErrorResponse.FieldError(
                            "reason", "사유는 1자 이상 500자 이하여야 합니다.")));
        }
        return reason.trim();
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private BusinessException conflict() {
        return new BusinessException(ErrorCode.ACTION_ALREADY_FINALIZED);
    }

    private String csvText(String value) {
        if (value == null) {
            return "";
        }
        String safe = value;
        if (!safe.isEmpty() && (safe.charAt(0) == '=' || safe.charAt(0) == '+'
                || safe.charAt(0) == '-' || safe.charAt(0) == '@'
                || safe.charAt(0) == '\t' || safe.charAt(0) == '\r')) {
            safe = "'" + safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private List<PreparedFile> prepareFiles(List<MultipartFile> files, String requestedScenario) {
        List<PreparedFile> prepared = new ArrayList<>(files.size());
        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            ValidatedUpload validation = productDocumentService.validateUpload(
                    file, requestedScenario, "files[" + index + "]");
            prepared.add(new PreparedFile(file, validation, checksum(file)));
        }
        return List.copyOf(prepared);
    }

    private List<StoredFile> storeAll(List<PreparedFile> files) {
        List<StoredFile> stored = new ArrayList<>(files.size());
        for (PreparedFile file : files) {
            StoredFile storedFile = fileStorage.store(
                    file.file(), file.validation().scenarioCode());
            stored.add(storedFile);
        }
        return stored;
    }

    private String manifestHash(Long productId, String scenario, List<PreparedFile> files) {
        MessageDigest digest = sha256();
        updateManifestField(digest, productId.toString());
        updateManifestField(digest, scenario);
        updateManifestField(digest, Integer.toString(files.size()));
        for (PreparedFile file : files) {
            updateManifestField(digest, file.validation().fileName());
            updateManifestField(digest, file.validation().mediaType().contentType());
            updateManifestField(digest, Long.toString(file.file().getSize()));
            updateManifestField(digest, file.validation().scenarioCode());
            updateManifestField(digest, file.checksum());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void updateManifestField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private String checksum(MultipartFile file) {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[DIGEST_BUFFER_SIZE];
        try (InputStream input = new DigestInputStream(file.getInputStream(), digest)) {
            while (input.read(buffer) != -1) {
                // DigestInputStream updates the digest.
            }
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 스트림을 읽지 못했습니다: " + file.getOriginalFilename(), e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다.", e);
        }
    }

    private void validateFileCount(List<MultipartFile> files) {
        int size = files == null ? 0 : files.size();
        if (size < MIN_FILE_COUNT || size > MAX_FILE_COUNT) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    List.of(new ErrorResponse.FieldError(
                            "files", "파일은 1개 이상 100개 이하로 업로드해야 합니다.")));
        }
    }

    private String resolveIdempotencyKey(String requestedKey) {
        if (requestedKey == null) {
            return "generated-" + UUID.randomUUID();
        }
        String key = requestedKey.trim();
        if (key.isEmpty() || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    List.of(new ErrorResponse.FieldError(
                            "Idempotency-Key", "멱등성 키는 1자 이상 200자 이하여야 합니다.")));
        }
        return key;
    }

    private record PreparedFile(
            MultipartFile file,
            ValidatedUpload validation,
            String checksum
    ) {
    }
}
