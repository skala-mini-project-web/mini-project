package com.crosschecklab.analysis.application;

import com.crosschecklab.analysis.provider.http.AiServiceProperties;
import com.crosschecklab.domain.analysis.Analysis;
import com.crosschecklab.domain.analysis.AnalysisGroundTruthFactSnapshot;
import com.crosschecklab.domain.analysis.AnalysisGroundTruthFactSnapshotRepository;
import com.crosschecklab.domain.analysis.AnalysisRepository;
import com.crosschecklab.domain.analysis.Finding;
import com.crosschecklab.domain.analysis.FindingRepository;
import com.crosschecklab.domain.analysis.dto.AnalysisAcceptedResponse;
import com.crosschecklab.domain.analysis.dto.AnalysisCreateRequest;
import com.crosschecklab.domain.analysis.dto.AnalysisResultResponse;
import com.crosschecklab.domain.analysis.dto.AnalysisStatusResponse;
import com.crosschecklab.domain.audit.AuditAction;
import com.crosschecklab.domain.audit.AuditService;
import com.crosschecklab.domain.document.ProductDocument;
import com.crosschecklab.domain.document.ProductDocumentRepository;
import com.crosschecklab.domain.evidence.EvidenceDocument;
import com.crosschecklab.domain.evidence.EvidenceDocumentRepository;
import com.crosschecklab.domain.persona.PersonaTemplate;
import com.crosschecklab.domain.persona.PersonaTemplateRepository;
import com.crosschecklab.global.common.enums.PersonaCode;
import com.crosschecklab.global.common.enums.UserRole;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import com.crosschecklab.global.idempotency.IdempotencyClaim;
import com.crosschecklab.global.idempotency.IdempotencyClaim.IdempotencyOperation;
import com.crosschecklab.global.idempotency.IdempotencyClaimRepository;
import com.crosschecklab.global.security.DemoUser;
import com.crosschecklab.global.security.OwnershipChecker;
import com.crosschecklab.global.trace.TraceIdFilter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

// 분석 유스케이스 (ANA-001~004). 실제 실행은 AnalysisJobService 가 커밋 이후 비동기로 수행한다.
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private static final IdempotencyOperation CREATE_OPERATION = IdempotencyOperation.ANALYSIS_CREATE;

    private final AnalysisRepository analysisRepository;
    private final IdempotencyClaimRepository idempotencyClaimRepository;
    private final AnalysisGroundTruthFactSnapshotRepository factSnapshotRepository;
    private final FindingRepository findingRepository;
    private final ProductDocumentRepository productDocumentRepository;
    private final EvidenceDocumentRepository evidenceDocumentRepository;
    private final PersonaTemplateRepository personaTemplateRepository;
    private final AnalysisInputLoader inputLoader;
    private final OwnershipChecker ownershipChecker;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final AiServiceProperties aiServiceProperties;
    private final Clock clock;

    // ANA-001. 입력을 검증해 CREATED 로 저장하고 즉시 202 를 반환한다.
    @Transactional
    public AnalysisAcceptedResponse create(
            AnalysisCreateRequest request,
            String idempotencyKey,
            String scenarioCode,
            DemoUser currentUser) {
        String resolvedScenario = resolveScenario(scenarioCode);
        String fingerprint = createFingerprint(request, resolvedScenario);
        int acquired = idempotencyClaimRepository.tryAcquire(
                currentUser.id(), CREATE_OPERATION, idempotencyKey, fingerprint);
        IdempotencyClaim claim = idempotencyClaimRepository
                .findWithLockByActorIdAndOperationAndIdempotencyKey(
                        currentUser.id(), CREATE_OPERATION, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("acquired idempotency claim is missing"));

        if (acquired == 0) {
            if (!claim.getFingerprint().equals(fingerprint)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            if (!claim.isCompleted()) {
                throw new IllegalStateException("idempotency claim completed without an analysis");
            }
            return AnalysisAcceptedResponse.created(claim.getAnalysisId());
        }

        ownershipChecker.requireRole(currentUser, UserRole.PRODUCT_MANAGER);
        ProductDocument lockedDocument = inputLoader.lockDocument(request.productDocumentId());
        ownershipChecker.requireOwner(lockedDocument.getProduct().getOwnerId(), currentUser);
        AnalysisInput input = inputLoader.load(lockedDocument, request.redTeamPackId(),
                request.personaIds(), request.evidenceDocumentIds());

        Analysis analysis = Analysis.create(input.document().getId(), input.redTeamPack().getId(),
                input.personaIds(), input.evidenceDocumentIds(), input.inputHash());
        try {
            // 부분 UNIQUE 인덱스(product_document_id, input_hash) 위반을 지금 드러낸다.
            analysisRepository.saveAndFlush(analysis);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_ANALYSIS_REQUEST);
        }
        factSnapshotRepository.saveAll(input.knownFacts().stream()
                .map(fact -> AnalysisGroundTruthFactSnapshot.of(analysis, fact.source()))
                .toList());

        claim.complete(analysis.getId());
        auditService.append(
                currentUser, AuditAction.ANALYSIS_CREATED, analysis.getId(), null, analysis.getId());
        eventPublisher.publishEvent(new AnalysisRequestedEvent(
                analysis.getId(), resolvedScenario, currentRequestTraceId()));
        return AnalysisAcceptedResponse.created(analysis.getId());
    }

    // ANA-002. 조회만 하며 상태를 바꾸지 않는다.
    @Transactional(readOnly = true)
    public AnalysisStatusResponse getStatus(Long analysisId, DemoUser currentUser) {
        Analysis analysis = find(analysisId);
        ownershipChecker.requireOwnerOrReviewer(ownerIdOf(analysis.getProductDocumentId()), currentUser);
        return AnalysisStatusResponse.from(analysis);
    }

    // ANA-003. 새 Analysis 를 만들지 않고 같은 행을 RUNNING 으로 되돌린다.
    @Transactional
    public AnalysisAcceptedResponse retry(Long analysisId, String scenarioCode, DemoUser currentUser) {
        // 비소유자 요청이 잠금을 잡고 소유자를 지연시키지 않도록 소유권을 먼저 판정한다.
        // 상품 소유자는 변경되지 않으므로(products.owner_id updatable=false) 잠금 후 재검사가 필요 없다.
        Long productDocumentId = analysisRepository.findProductDocumentIdById(analysisId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        ownershipChecker.requireOwner(ownerIdOf(productDocumentId), currentUser);

        Analysis analysis = analysisRepository.findWithLockById(analysisId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        analysis.requireRetryable(OffsetDateTime.now(clock));
        analysis.markRunning();
        try {
            // FAILED 였던 행이 다시 활성화되면서 같은 입력의 다른 분석과 UNIQUE 로 부딪힐 수 있다.
            analysisRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_ANALYSIS_REQUEST);
        }

        auditService.append(
                currentUser, AuditAction.ANALYSIS_RETRIED, analysis.getId(), null, analysis.getId());
        eventPublisher.publishEvent(new AnalysisRequestedEvent(
                analysisId, resolveScenario(scenarioCode), currentRequestTraceId()));
        return AnalysisAcceptedResponse.from(analysis);
    }

    // ANA-004
    @Transactional(readOnly = true)
    public AnalysisResultResponse getResult(Long analysisId, DemoUser currentUser) {
        Analysis analysis = find(analysisId);
        ProductDocument document = loadDocument(analysis.getProductDocumentId());
        ownershipChecker.requireOwnerOrReviewer(document.getProduct().getOwnerId(), currentUser);
        analysis.requireCompleted();
        List<Finding> findings = findingRepository.findByAnalysisIdOrderByIdAsc(analysisId);
        List<AnalysisGroundTruthFactSnapshot> groundTruthFacts =
                factSnapshotRepository.findAllByAnalysisIdOrderByIdAsc(analysisId);

        Map<Long, PersonaCode> personaCodes = personaTemplateRepository.findAll().stream()
                .collect(Collectors.toMap(PersonaTemplate::getId, PersonaTemplate::getCode));
        Map<Long, EvidenceDocument> evidenceDocuments = evidenceDocumentRepository
                .findAllById(analysis.getEvidenceDocumentIds()).stream()
                .collect(Collectors.toMap(EvidenceDocument::getId, Function.identity()));

        return AnalysisResultResponse.of(
                analysis, document, groundTruthFacts, findings, personaCodes, evidenceDocuments);
    }

    // X-Demo-Scenario 헤더가 없으면 설정의 기본 시나리오를 쓴다.
    private String resolveScenario(String scenarioCode) {
        return StringUtils.hasText(scenarioCode) ? scenarioCode.trim() : aiServiceProperties.defaultScenarioCode();
    }

    private String createFingerprint(AnalysisCreateRequest request, String resolvedScenario) {
        String canonical = String.join("\n",
                "productDocumentId=" + request.productDocumentId(),
                "redTeamPackId=" + request.redTeamPackId(),
                "personaIds=" + normalizedIds(request.personaIds()),
                "evidenceDocumentIds=" + normalizedIds(request.evidenceDocumentIds()),
                "scenario=" + resolvedScenario.length() + ":" + resolvedScenario);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String normalizedIds(List<Long> ids) {
        return ids.stream()
                .distinct()
                .sorted(Comparator.nullsFirst(Comparator.naturalOrder()))
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    private String currentRequestTraceId() {
        return Objects.requireNonNull(
                TraceIdFilter.currentTraceId(), "analysis requests require a server trace");
    }

    // 소유자는 document → product → owner 로 파생한다.
    private Long ownerIdOf(Long productDocumentId) {
        return loadDocument(productDocumentId).getProduct().getOwnerId();
    }

    private ProductDocument loadDocument(Long productDocumentId) {
        return productDocumentRepository.findById(productDocumentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private Analysis find(Long analysisId) {
        return analysisRepository.findById(analysisId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
