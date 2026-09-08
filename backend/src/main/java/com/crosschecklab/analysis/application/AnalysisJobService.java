package com.crosschecklab.analysis.application;

import com.crosschecklab.analysis.provider.ProviderException;
import com.crosschecklab.analysis.provider.RiskAnalysisProvider;
import com.crosschecklab.analysis.provider.dto.AnalysisRequest;
import com.crosschecklab.analysis.provider.dto.AnalysisResult;
import com.crosschecklab.analysis.provider.dto.FindingPayload;
import com.crosschecklab.analysis.rag.EvidenceChunkIndexer;
import com.crosschecklab.analysis.rag.PgVectorEvidenceRetriever;
import com.crosschecklab.analysis.rag.RagRetrievedChunk;
import com.crosschecklab.domain.analysis.Analysis;
import com.crosschecklab.domain.analysis.AnalysisExecution;
import com.crosschecklab.domain.analysis.AnalysisExecutionRepository;
import com.crosschecklab.domain.analysis.AnalysisRagRun;
import com.crosschecklab.domain.analysis.AnalysisRagRunRepository;
import com.crosschecklab.domain.analysis.AnalysisRepository;
import com.crosschecklab.domain.analysis.Finding;
import com.crosschecklab.domain.analysis.FindingEvidenceAnchor;
import com.crosschecklab.domain.analysis.FindingEvidenceAnchorRepository;
import com.crosschecklab.domain.analysis.FindingRepository;
import com.crosschecklab.domain.audit.AuditAction;
import com.crosschecklab.domain.audit.AuditEvent;
import com.crosschecklab.domain.audit.AuditEventRepository;
import com.crosschecklab.domain.document.DocumentSourceRevision;
import com.crosschecklab.domain.document.DocumentSourceRevisionRepository;
import com.crosschecklab.domain.document.ProductDocument;
import com.crosschecklab.domain.document.ProductDocumentRepository;
import com.crosschecklab.domain.evidence.EvidenceDocument;
import com.crosschecklab.domain.evidence.EvidenceDocumentChunk;
import com.crosschecklab.domain.evidence.EvidenceDocumentChunkRepository;
import com.crosschecklab.domain.persona.PersonaTemplate;
import com.crosschecklab.domain.persona.PersonaTemplateRepository;
import com.crosschecklab.global.common.enums.AnalysisStatus;
import com.crosschecklab.global.common.enums.ExtractStatus;
import com.crosschecklab.global.common.enums.PersonaCode;
import com.crosschecklab.global.common.enums.RedTeamRuleCode;
import com.crosschecklab.global.config.AsyncConfig;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

// 백그라운드 분석 실행. 202 로 수락된 뒤 CREATED → RUNNING → COMPLETED/FAILED 를 진행한다.
//
// 트랜잭션을 3토막으로 끊는 이유:
// RUNNING 을 먼저 커밋해야 Polling 이 진행 상태를 볼 수 있고, 외부 HTTP 호출이 DB 커넥션을 물고 있으면 안 된다.
@Slf4j
@Service
public class AnalysisJobService {

    private static final String RETRIEVAL_VERSION = "pgvector-cosine-v1";
    private static final String FINDING_LINEAGE_NAMESPACE = "com.crosschecklab.finding-lineage:v1";
    private static final int TEXT_LAYER_PAGE = 1;
    private static final Duration STALE_ANALYSIS_AFTER = Duration.ofMinutes(5);
    private static final int RECOVERY_BATCH_SIZE = 25;
    private static final int MAX_VERSION_LENGTH = 50;
    private static final int MAX_STATEMENT_LENGTH = 1_000;
    private static final int MAX_RECOMMENDATION_LENGTH = 1_000;
    private static final int MAX_PERSONA_CODES = 12;
    private static final int MAX_RETRIEVED_CONTEXT_CHUNK_IDS = 20;
    private static final int MAX_KNOWN_FACT_IDS = 50;
    private static final int MAX_EVIDENCE_SPANS = 60;

    private final AnalysisRepository analysisRepository;
    private final AnalysisExecutionRepository analysisExecutionRepository;
    private final EntityManager entityManager;
    private final FindingRepository findingRepository;
    private final FindingEvidenceAnchorRepository findingEvidenceAnchorRepository;
    private final ProductDocumentRepository productDocumentRepository;
    private final DocumentSourceRevisionRepository documentSourceRevisionRepository;
    private final AnalysisRagRunRepository ragRunRepository;
    private final EvidenceDocumentChunkRepository evidenceChunkRepository;
    private final PersonaTemplateRepository personaTemplateRepository;
    private final AnalysisInputLoader inputLoader;
    private final EvidenceChunkIndexer evidenceChunkIndexer;
    private final PgVectorEvidenceRetriever evidenceRetriever;
    private final RiskAnalysisProvider provider;
    private final EvidenceRiskScoreService evidenceRiskScoreService;
    private final AuditEventRepository auditEventRepository;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate withoutTransaction;
    private final Clock clock;

    public AnalysisJobService(AnalysisRepository analysisRepository,
                              AnalysisExecutionRepository analysisExecutionRepository,
                              EntityManager entityManager,
                              FindingRepository findingRepository,
                              FindingEvidenceAnchorRepository findingEvidenceAnchorRepository,
                              ProductDocumentRepository productDocumentRepository,
                              DocumentSourceRevisionRepository documentSourceRevisionRepository,
                              AnalysisRagRunRepository ragRunRepository,
                              EvidenceDocumentChunkRepository evidenceChunkRepository,
                              PersonaTemplateRepository personaTemplateRepository, AnalysisInputLoader inputLoader,
                              EvidenceChunkIndexer evidenceChunkIndexer,
                              PgVectorEvidenceRetriever evidenceRetriever,
                              RiskAnalysisProvider provider,
                              EvidenceRiskScoreService evidenceRiskScoreService,
                              AuditEventRepository auditEventRepository,
                              PlatformTransactionManager transactionManager, Clock clock) {
        this.analysisRepository = analysisRepository;
        this.analysisExecutionRepository = analysisExecutionRepository;
        this.entityManager = entityManager;
        this.findingRepository = findingRepository;
        this.findingEvidenceAnchorRepository = findingEvidenceAnchorRepository;
        this.productDocumentRepository = productDocumentRepository;
        this.documentSourceRevisionRepository = documentSourceRevisionRepository;
        this.ragRunRepository = ragRunRepository;
        this.evidenceChunkRepository = evidenceChunkRepository;
        this.personaTemplateRepository = personaTemplateRepository;
        this.inputLoader = inputLoader;
        this.evidenceChunkIndexer = evidenceChunkIndexer;
        this.evidenceRetriever = evidenceRetriever;
        this.provider = provider;
        this.evidenceRiskScoreService = evidenceRiskScoreService;
        this.auditEventRepository = auditEventRepository;
        this.clock = clock;
        // REQUIRES_NEW: 이 작업의 상태 전이는 요청 트랜잭션과 완전히 독립적으로 커밋되어야 한다.
        // (AFTER_COMMIT 콜백 안에서는 이미 완료된 트랜잭션에 합류해 커밋이 유실될 수 있다)
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.withoutTransaction = new TransactionTemplate(transactionManager);
        this.withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    // CREATED/RUNNING 행이 커밋된 뒤에 시작해야 작업 스레드가 해당 행을 읽을 수 있다.
    @Async(AsyncConfig.ANALYSIS_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(AnalysisRequestedEvent event) {
        run(
                event.analysisId(),
                event.expectedExecutionToken(),
                event.scenarioCode(),
                event.traceId());
    }

    // 프로세스가 CREATED/RUNNING 커밋 뒤 종료되면 in-memory executor 에 작업이 남지 않는다.
    // 작은 batch 를 주기적으로 복구하고, 각 행은 별도 잠금 트랜잭션에서 다시 검증한다.
    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT30S")
    public void recoverStaleAnalyses() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(STALE_ANALYSIS_AFTER);
        List<AnalysisRepository.StaleAnalysisCandidate> staleCandidates =
                analysisRepository.findStaleAnalysisCandidates(
                        cutoff, PageRequest.of(0, RECOVERY_BATCH_SIZE));
        for (AnalysisRepository.StaleAnalysisCandidate staleCandidate : staleCandidates) {
            try {
                transactionTemplate.execute(status -> recoverStaleAnalysis(staleCandidate, cutoff));
            } catch (RuntimeException e) {
                log.error(
                        "오래된 {} 분석 {} 복구 실패 executionToken={}",
                        staleCandidate.getStatus(),
                        staleCandidate.getId(),
                        staleCandidate.getExecutionToken(),
                        e);
            }
        }
    }

    private boolean recoverStaleAnalysis(
            AnalysisRepository.StaleAnalysisCandidate staleCandidate,
            OffsetDateTime cutoff
    ) {
        Analysis analysis = analysisRepository.findStaleAnalysisWithLock(
                        staleCandidate.getId(), staleCandidate.getExecutionToken(), cutoff)
                .orElse(null);
        if (analysis == null) {
            return false;
        }
        if (analysis.getStatus() == AnalysisStatus.CREATED) {
            analysis.fail(ErrorCode.AI_SERVICE_TEMPORARY_FAILURE, true);
            appendTerminalAudit(
                    "analysis-recovery-" + UUID.randomUUID(),
                    AuditAction.ANALYSIS_FAILED,
                    analysis.getId());
            analysisRepository.flush();
            log.warn("오래된 CREATED 분석 {} 을 FAILED 로 복구", analysis.getId());
            return true;
        }
        AnalysisExecution execution = analysisExecutionRepository
                .findByAnalysisIdAndExecutionToken(
                        analysis.getId(), staleCandidate.getExecutionToken())
                .orElse(null);
        if (execution != null && !execution.isRunning()) {
            throw new IllegalStateException(
                    "분석 %d 실행 %s 이 RUNNING 상태가 아닙니다."
                            .formatted(analysis.getId(), staleCandidate.getExecutionToken()));
        }
        if (execution != null) {
            execution.fail(
                    ErrorCode.AI_SERVICE_TEMPORARY_FAILURE.name(),
                    true,
                    OffsetDateTime.now(clock));
        }
        analysis.fail(ErrorCode.AI_SERVICE_TEMPORARY_FAILURE, true);
        appendTerminalAudit(
                "analysis-recovery-" + UUID.randomUUID(),
                AuditAction.ANALYSIS_FAILED,
                analysis.getId());
        analysisRepository.flush();
        log.warn(
                "오래된 RUNNING 분석 {} 을 FAILED 로 복구 executionPresent={}",
                analysis.getId(),
                execution != null);
        return true;
    }

    private void run(
            Long analysisId,
            String expectedExecutionToken,
            String scenarioCode,
            String traceId
    ) {
        String fence = transactionTemplate.execute(
                status -> beginExecution(analysisId, expectedExecutionToken));
        if (fence == null) {
            return;
        }
        Job job;
        try {
            job = withoutTransaction.execute(status -> prepareJob(analysisId, scenarioCode, fence));
            if (job == null) {
                return;
            }
        } catch (BusinessException e) {
            // 생성 이후 입력이 바뀐 경우(문서 확정 해제, 근거 비활성화 등) 원인 코드를 그대로 남긴다.
            log.warn("분석 {} 입력이 더 이상 유효하지 않음 errorCode={}", analysisId, e.getErrorCode());
            markFailed(analysisId, e.getErrorCode(), false, fence, traceId);
            return;
        } catch (RagPreparationException e) {
            log.warn("분석 {} 근거 검색 준비 실패", analysisId, e);
            markFailed(analysisId, ErrorCode.AI_SERVICE_TEMPORARY_FAILURE, true, fence, traceId);
            return;
        } catch (RuntimeException e) {
            log.error("분석 {} 입력 준비 실패", analysisId, e);
            markFailed(analysisId, ErrorCode.INTERNAL_ERROR, false, fence, traceId);
            return;
        }

        try {
            AnalysisResult result = provider.analyze(job.request());
            validateProviderReferences(job.request(), result);
            validateAnchorPlan(result, job);
            transactionTemplate.execute(
                    status -> saveResult(analysisId, result, job, traceId));
        } catch (BusinessException e) {
            log.warn("분석 {} 원본 문서가 더 이상 유효하지 않음 errorCode={}", analysisId, e.getErrorCode());
            markFailed(analysisId, e.getErrorCode(), false, job.fence(), traceId);
        } catch (ProviderException e) {
            log.warn(
                    "분석 {} 실패 errorCode={} retryable={} detail={}",
                    analysisId,
                    e.getErrorCode(),
                    e.isRetryable(),
                    e.getMessage());
            markFailed(analysisId, e.getErrorCode(), e.isRetryable(), job.fence(), traceId);
        } catch (RuntimeException e) {
            log.error("분석 {} 결과 저장 실패", analysisId, e);
            markFailed(analysisId, ErrorCode.INTERNAL_ERROR, false, job.fence(), traceId);
        }
    }

    // fence = 매 실행마다 새로 발급해 영속화한 token. 같은 상태/진행률의 재시도도 이전 결과를 차단한다.
    private record Job(
            AnalysisRequest request,
            String fence,
            String queryHash,
            List<RagRetrievedChunk> retrievedChunks,
            String embeddingModel,
            String chunkingVersion,
            OffsetDateTime retrievedAt,
            DocumentSnapshot documentSnapshot,
            Map<Long, EvidenceSourceSnapshot> evidenceSources
    ) {
    }

    private record DocumentSnapshot(long documentId, String sourceHash, String text) {
    }

    private record EvidenceSourceSnapshot(long evidenceDocumentId, String sourceHash, String text) {
    }

    private record ResultPersistencePlan(
            List<Finding> findings,
            List<AnchorPersistencePlan> anchors
    ) {
    }

    private record AnchorPersistencePlan(
            Finding finding,
            FindingEvidenceAnchor.SourceRole sourceRole,
            Long sourceDocumentId,
            Long sourceRevisionId,
            Long evidenceDocumentId,
            Long retrievedChunkId,
            String sourceHash,
            int pageNumber,
            long utf8StartOffset,
            long utf8EndOffset,
            String excerptHash,
            String exactExcerpt
    ) {
        private AnchorPersistencePlan {
            Objects.requireNonNull(finding, "finding");
            Objects.requireNonNull(sourceRole, "sourceRole");
            if (sourceRole == FindingEvidenceAnchor.SourceRole.DOCUMENT_CLAIM) {
                requirePositive(sourceDocumentId, "sourceDocumentId");
                requirePositive(sourceRevisionId, "sourceRevisionId");
                if (evidenceDocumentId != null || retrievedChunkId != null) {
                    throw new IllegalArgumentException("document claim must not identify policy evidence");
                }
            } else {
                requirePositive(evidenceDocumentId, "evidenceDocumentId");
                requirePositive(retrievedChunkId, "retrievedChunkId");
                if (sourceDocumentId != null || sourceRevisionId != null) {
                    throw new IllegalArgumentException("policy requirement must not identify a source document");
                }
            }
            requireSha256(sourceHash, "sourceHash");
            requireSha256(excerptHash, "excerptHash");
            if (pageNumber <= 0) {
                throw new IllegalArgumentException("pageNumber must be positive");
            }
            if (exactExcerpt == null || exactExcerpt.isEmpty()) {
                throw new IllegalArgumentException("exactExcerpt must not be empty");
            }
            if (utf8StartOffset < 0 || utf8EndOffset <= utf8StartOffset
                    || utf8EndOffset - utf8StartOffset
                    != exactExcerpt.getBytes(StandardCharsets.UTF_8).length) {
                throw new IllegalArgumentException("invalid exact evidence UTF-8 range");
            }
            if (!sha256(exactExcerpt).equals(excerptHash)) {
                throw new IllegalArgumentException("excerptHash must match exactExcerpt");
            }
        }

        private static AnchorPersistencePlan documentClaim(
                Finding finding,
                long sourceDocumentId,
                long sourceRevisionId,
                String sourceHash,
                int pageNumber,
                Utf8Range range,
                String exactExcerpt
        ) {
            return new AnchorPersistencePlan(
                    finding,
                    FindingEvidenceAnchor.SourceRole.DOCUMENT_CLAIM,
                    sourceDocumentId,
                    sourceRevisionId,
                    null,
                    null,
                    sourceHash,
                    pageNumber,
                    range.start(),
                    range.end(),
                    sha256(exactExcerpt),
                    exactExcerpt);
        }

        private static AnchorPersistencePlan policyRequirement(
                Finding finding,
                long evidenceDocumentId,
                long retrievedChunkId,
                String sourceHash,
                int pageNumber,
                Utf8Range range,
                String exactExcerpt
        ) {
            return new AnchorPersistencePlan(
                    finding,
                    FindingEvidenceAnchor.SourceRole.POLICY_REQUIREMENT,
                    null,
                    null,
                    evidenceDocumentId,
                    retrievedChunkId,
                    sourceHash,
                    pageNumber,
                    range.start(),
                    range.end(),
                    sha256(exactExcerpt),
                    exactExcerpt);
        }

        private FindingEvidenceAnchor toEntity() {
            if (sourceRole == FindingEvidenceAnchor.SourceRole.DOCUMENT_CLAIM) {
                return FindingEvidenceAnchor.documentClaim(
                        finding,
                        sourceDocumentId,
                        sourceRevisionId,
                        sourceHash,
                        pageNumber,
                        utf8StartOffset,
                        utf8EndOffset,
                        excerptHash,
                        exactExcerpt);
            }
            return FindingEvidenceAnchor.policyRequirement(
                    finding,
                    evidenceDocumentId,
                    retrievedChunkId,
                    sourceHash,
                    pageNumber,
                    utf8StartOffset,
                    utf8EndOffset,
                    excerptHash,
                    exactExcerpt);
        }

        private static void requirePositive(Long value, String fieldName) {
            if (value == null || value <= 0) {
                throw new IllegalArgumentException(fieldName + " must be positive");
            }
        }

        private static void requireSha256(String value, String fieldName) {
            if (value == null || !value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(fieldName + " must be lowercase SHA-256 hex");
            }
        }
    }

    private String beginExecution(Long analysisId, String expectedExecutionToken) {
        Analysis analysis = findWithLock(analysisId);
        if (!Objects.equals(expectedExecutionToken, analysis.getExecutionToken())) {
            return null;
        }
        if (analysis.getStatus() == AnalysisStatus.CREATED) {
            analysis.markRunning();
        } else if (analysis.getStatus() != AnalysisStatus.RUNNING) {
            return null;
        }
        String fence = analysis.getExecutionToken();
        if (analysisExecutionRepository.findByExecutionToken(fence).isPresent()) {
            return null;
        }
        AnalysisExecution previousExecution = analysisExecutionRepository
                .findTopByAnalysisIdOrderByAttemptNoDesc(analysisId)
                .orElse(null);
        if (previousExecution != null && previousExecution.isRunning()) {
            previousExecution.discard("EXECUTION_SUPERSEDED", OffsetDateTime.now(clock));
        }
        int attemptNo = previousExecution == null ? 1 : previousExecution.getAttemptNo() + 1;
        analysisExecutionRepository.save(AnalysisExecution.start(
                analysis,
                attemptNo,
                fence,
                RETRIEVAL_VERSION,
                OffsetDateTime.now(clock)));
        entityManager.flush();
        return fence;
    }

    private Job prepareJob(Long analysisId, String scenarioCode, String fence) {
        AnalysisInput input = transactionTemplate.execute(status -> {
            Analysis analysis = find(analysisId);
            return isCurrent(analysis, fence) ? inputLoader.load(analysis) : null;
        });
        if (input == null) {
            return null;
        }
        List<RagRetrievedChunk> retrievedChunks;
        try {
            evidenceChunkIndexer.indexSelected(input.evidenceDocumentIds());
            retrievedChunks = evidenceRetriever.retrieve(
                    input.retrievalQuery(), input.evidenceDocumentIds());
            requireConsistentRetrieval(retrievedChunks);
        } catch (RuntimeException e) {
            throw new RagPreparationException(e);
        }
        AnalysisRequest request = input.toProviderRequest(analysisId, scenarioCode, retrievedChunks);
        RagRetrievedChunk first = retrievedChunks.getFirst();
        Job job = new Job(
                request,
                fence,
                input.retrievalQueryHash(),
                List.copyOf(retrievedChunks),
                first.embeddingModel(),
                first.chunkingVersion(),
                OffsetDateTime.now(clock),
                new DocumentSnapshot(
                        input.document().getId(),
                        input.document().getChecksum(),
                        input.document().getExtractedText()),
                input.evidenceDocuments().stream().collect(Collectors.toUnmodifiableMap(
                        EvidenceDocument::getId,
                        evidence -> {
                            String text = normalizeEvidenceContent(evidence.getContent());
                            return new EvidenceSourceSnapshot(evidence.getId(), sha256(text), text);
                        })));
        return transactionTemplate.execute(status -> {
            Analysis currentAnalysis = findWithLock(analysisId);
            if (!isCurrent(currentAnalysis, fence)) {
                return null;
            }
            requirePinnedConfirmedSource(currentAnalysis, job.documentSnapshot());
            AnalysisExecution execution = findRunningExecution(analysisId, fence);
            saveRagRun(execution, job);
            return job;
        });
    }

    private void requireConsistentRetrieval(List<RagRetrievedChunk> retrievedChunks) {
        if (retrievedChunks == null || retrievedChunks.isEmpty()) {
            throw new IllegalStateException("선택한 근거 문서에서 검색된 청크가 없습니다.");
        }
        RagRetrievedChunk first = retrievedChunks.getFirst();
        Set<Long> chunkIds = new LinkedHashSet<>();
        Set<Integer> ranks = new LinkedHashSet<>();
        for (RagRetrievedChunk chunk : retrievedChunks) {
            if (!Objects.equals(first.embeddingModel(), chunk.embeddingModel())
                    || !Objects.equals(first.chunkingVersion(), chunk.chunkingVersion())
                    || !chunkIds.add(chunk.chunkId())
                    || !ranks.add(chunk.rank())) {
                throw new IllegalStateException("근거 검색 결과가 일관되지 않습니다.");
            }
        }
    }

    private void validateProviderReferences(AnalysisRequest request, AnalysisResult result) {
        if (result == null) {
            throw invalidProviderResponse("응답이 비어 있음");
        }
        if (result.findings() == null) {
            throw invalidProviderResponse("findings 가 없음");
        }
        if (result.findings().size() > AnalysisResult.MAX_FINDINGS) {
            throw invalidProviderResponse("findings 개수 초과: " + result.findings().size());
        }
        if (result.findings().isEmpty() && result.riskScore() != null) {
            throw invalidProviderResponse("findings 가 비어 있으면 riskScore 는 null 이어야 함");
        }
        if (result.riskScore() != null && (result.riskScore() < 0 || result.riskScore() > 100)) {
            throw invalidProviderResponse("riskScore 범위 초과: " + result.riskScore());
        }
        requireProviderNonBlank(result.modelVersion(), "modelVersion", MAX_VERSION_LENGTH);
        requireProviderNonBlank(result.promptVersion(), "promptVersion", MAX_VERSION_LENGTH);

        Set<RedTeamRuleCode> selectedRuleCodes = request.ruleCodes() == null
                ? Set.of()
                : Set.copyOf(request.ruleCodes());
        Set<PersonaCode> selectedPersonaCodes = request.personaCodes() == null
                ? Set.of()
                : Set.copyOf(request.personaCodes());
        Set<Long> acceptedChunkIds = request.retrievedContexts() == null ? Set.of()
                : request.retrievedContexts().stream()
                        .map(AnalysisRequest.RetrievedContextPayload::chunkId)
                        .collect(Collectors.toSet());
        Set<Long> acceptedFactIds = request.knownFacts() == null ? Set.of() : request.knownFacts().stream()
                .map(AnalysisRequest.KnownFactPayload::factId)
                .collect(Collectors.toSet());

        for (FindingPayload finding : result.findings()) {
            if (finding == null) {
                throw invalidProviderResponse("finding 이 비어 있음");
            }
            requireProviderNonBlank(finding.statement(), "finding.statement", MAX_STATEMENT_LENGTH);
            if (finding.severity() == null) {
                throw invalidProviderResponse("finding 에 severity 가 없음");
            }
            if (finding.recommendation() != null
                    && finding.recommendation().length() > MAX_RECOMMENDATION_LENGTH) {
                throw invalidProviderResponse("finding.recommendation 길이 초과");
            }
            if (finding.policyRuleCode() == null) {
                throw invalidProviderResponse("finding 에 policyRuleCode 가 없음");
            }
            if (!selectedRuleCodes.contains(finding.policyRuleCode())) {
                throw invalidProviderResponse(
                        "요청에서 선택하지 않은 policyRuleCode: " + finding.policyRuleCode());
            }
            // FastAPI 검증과 별개로 Spring이 다시 확인한다. 선택하지 않은 persona는 현재 execution에 저장하지 않는다.
            if (finding.affectedPersonaCodes() == null) {
                throw invalidProviderResponse("finding 에 affectedPersonaCodes 가 없음");
            }
            if (finding.affectedPersonaCodes().isEmpty()) {
                throw invalidProviderResponse("finding 에 affectedPersonaCodes 가 비어 있음");
            }
            if (finding.affectedPersonaCodes().size() > MAX_PERSONA_CODES) {
                throw invalidProviderResponse(
                        "affectedPersonaCodes 개수 초과: " + finding.affectedPersonaCodes().size());
            }
            Set<PersonaCode> citedPersonaCodes = new LinkedHashSet<>();
            for (PersonaCode personaCode : finding.affectedPersonaCodes()) {
                if (personaCode == null) {
                    throw invalidProviderResponse("persona 인용에 code 가 없음");
                }
                if (!citedPersonaCodes.add(personaCode)) {
                    throw invalidProviderResponse("중복된 persona 인용: " + personaCode);
                }
                if (!selectedPersonaCodes.contains(personaCode)) {
                    throw invalidProviderResponse("요청에서 선택하지 않은 persona: " + personaCode);
                }
            }
            if (finding.retrievedContextChunkIds() == null
                    || finding.retrievedContextChunkIds().isEmpty()) {
                throw invalidProviderResponse("finding 에 retrievedContextChunkIds 가 없음");
            }
            if (finding.retrievedContextChunkIds().size() > MAX_RETRIEVED_CONTEXT_CHUNK_IDS) {
                throw invalidProviderResponse("retrievedContextChunkIds 개수 초과: "
                        + finding.retrievedContextChunkIds().size());
            }
            Set<Long> citedChunkIds = new LinkedHashSet<>();
            for (Long chunkId : finding.retrievedContextChunkIds()) {
                if (chunkId == null) {
                    throw invalidProviderResponse("근거 인용에 retrievedContextChunkId 가 없음");
                }
                if (!citedChunkIds.add(chunkId)) {
                    throw invalidProviderResponse("중복된 검색 근거 청크 인용: " + chunkId);
                }
                if (!acceptedChunkIds.contains(chunkId)) {
                    throw invalidProviderResponse("요청에서 검색되지 않은 근거 청크 인용: " + chunkId);
                }
            }
            if (finding.knownFactIds() == null) {
                throw invalidProviderResponse("finding 에 knownFactIds 가 없음");
            }
            if (finding.knownFactIds().size() > MAX_KNOWN_FACT_IDS) {
                throw invalidProviderResponse(
                        "knownFactIds 개수 초과: " + finding.knownFactIds().size());
            }
            Set<Long> citedFactIds = new LinkedHashSet<>();
            for (Long factId : finding.knownFactIds()) {
                if (factId == null) {
                    throw invalidProviderResponse("사실 인용에 factId 가 없음");
                }
                if (!citedFactIds.add(factId)) {
                    throw invalidProviderResponse("중복된 사실 인용: " + factId);
                }
                if (!acceptedFactIds.contains(factId)) {
                    throw invalidProviderResponse("요청에 없는 사실 인용: " + factId);
                }
            }
            validateEvidenceSpanReferences(finding, citedChunkIds);
        }
    }

    private void validateEvidenceSpanReferences(FindingPayload finding, Set<Long> citedChunkIds) {
        if (finding.evidenceSpans() == null || finding.evidenceSpans().isEmpty()) {
            throw invalidProviderResponse("finding 에 evidenceSpans 가 없음");
        }
        if (finding.evidenceSpans().size() > MAX_EVIDENCE_SPANS) {
            throw invalidProviderResponse("evidenceSpans 개수 초과: " + finding.evidenceSpans().size());
        }
        Set<Long> spannedChunkIds = new LinkedHashSet<>();
        Set<String> uniqueSpans = new LinkedHashSet<>();
        for (FindingPayload.EvidenceSpanPayload span : finding.evidenceSpans()) {
            if (span == null || span.chunkId() == null || span.excerpt() == null
                    || span.excerpt().isBlank()) {
                throw invalidProviderResponse("근거 범위에 chunkId 또는 excerpt 가 없음");
            }
            if (!citedChunkIds.contains(span.chunkId())) {
                throw invalidProviderResponse("인용하지 않은 근거 청크의 범위: " + span.chunkId());
            }
            if (!uniqueSpans.add(span.chunkId() + "\0" + span.excerpt())) {
                throw invalidProviderResponse("중복된 근거 범위: " + span.chunkId());
            }
            spannedChunkIds.add(span.chunkId());
        }
        if (!spannedChunkIds.equals(citedChunkIds)) {
            throw invalidProviderResponse("인용한 모든 근거 청크에 exact evidence span 이 필요함");
        }
    }

    private ProviderException invalidProviderResponse(String detail) {
        return new ProviderException(ErrorCode.PROVIDER_RESPONSE_INVALID, false, detail);
    }

    private void requireProviderNonBlank(String value, String fieldName, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw invalidProviderResponse(fieldName + " 이 비어 있음");
        }
        if (value.length() > maximumLength) {
            throw invalidProviderResponse(fieldName + " 길이 초과");
        }
    }

    private void validateAnchorPlan(AnalysisResult result, Job job) {
        Map<Long, RagRetrievedChunk> contextsByChunkId = job.retrievedChunks().stream()
                .collect(Collectors.toMap(RagRetrievedChunk::chunkId, context -> context));

        for (FindingPayload finding : result.findings()) {
            for (FindingPayload.EvidenceSpanPayload span : finding.evidenceSpans()) {
                RagRetrievedChunk context = contextsByChunkId.get(span.chunkId());
                if (context == null || !finding.retrievedContextChunkIds().contains(span.chunkId())) {
                    throw invalidProviderResponse("인용하지 않은 근거 청크의 범위: " + span.chunkId());
                }
                uniqueUtf8Range(
                        context.chunkText(),
                        span.excerpt(),
                        "retrieved chunk " + span.chunkId());
                EvidenceSourceSnapshot source = job.evidenceSources().get(context.evidenceDocumentId());
                if (source == null || source.evidenceDocumentId() != context.evidenceDocumentId()
                        || !Objects.equals(source.sourceHash(), context.sourceHash())) {
                    throw invalidProviderResponse("검색 근거의 source/hash snapshot 이 일치하지 않음: "
                            + span.chunkId());
                }
                uniqueUtf8Range(
                        source.text(),
                        span.excerpt(),
                        "evidence source " + source.evidenceDocumentId());
            }
        }
    }

    // 잠근 행을 기준으로 이 회차가 아직 유효한가. 그 사이 재시도나 복구 전이가 있었으면 false.
    private boolean isCurrent(Analysis analysis, String fence) {
        boolean current = fence != null
                && analysis.getStatus() == AnalysisStatus.RUNNING
                && fence.equals(analysis.getExecutionToken());
        if (!current) {
            log.warn("분석 {} 이전 회차 결과를 버린다 (상태 전이 또는 재시도가 이미 발생함)", analysis.getId());
        }
        return current;
    }

    private boolean saveResult(Long analysisId, AnalysisResult result, Job job, String traceId) {
        Analysis analysis = findWithLock(analysisId);
        if (!isCurrent(analysis, job.fence())) {
            return false;
        }
        AnalysisExecution execution = findRunningExecution(analysisId, job.fence());
        DocumentSourceRevision sourceRevision = requirePinnedConfirmedSource(analysis, job.documentSnapshot());

        Map<PersonaCode, Long> personaIdsByCode = personaTemplateRepository.findAll().stream()
                .collect(Collectors.toMap(PersonaTemplate::getCode, PersonaTemplate::getId));
        Map<Long, RagRetrievedChunk> contextsByChunkId =
                job.retrievedChunks().stream()
                        .collect(Collectors.toMap(
                                RagRetrievedChunk::chunkId,
                                context -> context));

        ResultPersistencePlan persistencePlan = planResultPersistence(
                result, job, execution, sourceRevision, personaIdsByCode, contextsByChunkId);
        findingRepository.saveAll(persistencePlan.findings());
        entityManager.flush();
        findingEvidenceAnchorRepository.saveAll(persistencePlan.anchors().stream()
                .map(AnchorPersistencePlan::toEntity)
                .toList());
        entityManager.flush();
        OffsetDateTime finishedAt = OffsetDateTime.now(clock);
        execution.succeed(
                result.riskScore(), result.modelVersion(), result.promptVersion(), finishedAt);
        entityManager.flush();
        analysis.complete(result.modelVersion(), result.promptVersion(), finishedAt);
        analysis.markCurrentSuccessfulExecution(execution);
        entityManager.flush();
        evidenceRiskScoreService.createPendingReview(execution);
        appendTerminalAudit(traceId, AuditAction.ANALYSIS_COMPLETED, analysisId);
        analysisRepository.flush();
        return true;
    }

    private ResultPersistencePlan planResultPersistence(
            AnalysisResult result,
            Job job,
            AnalysisExecution execution,
            DocumentSourceRevision sourceRevision,
            Map<PersonaCode, Long> personaIdsByCode,
            Map<Long, RagRetrievedChunk> contextsByChunkId
    ) {
        List<Finding> findings = new ArrayList<>();
        List<AnchorPersistencePlan> anchors = new ArrayList<>();
        for (int ordinal = 0; ordinal < result.findings().size(); ordinal++) {
            FindingPayload payload = result.findings().get(ordinal);
            Finding finding = Finding.create(
                    execution,
                    lineageId(job.fence(), ordinal),
                    1,
                    null,
                    payload.statement(),
                    payload.severity(),
                    payload.policyRuleCode(),
                    payload.recommendation(),
                    personaIds(payload.affectedPersonaCodes(), personaIdsByCode));
            payload.retrievedContextChunkIds().stream()
                    .map(contextsByChunkId::get)
                    .forEach(context -> finding.addEvidenceReference(
                            context.evidenceDocumentId(), context.chunkText()));
            List<AnchorPersistencePlan> findingAnchors = planAnchors(
                    finding, payload, job, sourceRevision, contextsByChunkId);
            findings.add(finding);
            anchors.addAll(findingAnchors);
        }
        return new ResultPersistencePlan(List.copyOf(findings), List.copyOf(anchors));
    }

    private DocumentSourceRevision requirePinnedConfirmedSource(
            Analysis analysis, DocumentSnapshot snapshot) {
        ProductDocument document = productDocumentRepository.findById(analysis.getProductDocumentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        if (document.getExtractStatus() != ExtractStatus.READY || !document.isConfirmed()) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_CONFIRMED);
        }
        if (!Objects.equals(document.getId(), snapshot.documentId())
                || !Objects.equals(document.getChecksum(), snapshot.sourceHash())
                || !Objects.equals(document.getExtractedText(), snapshot.text())) {
            throw invalidProviderResponse("Provider 요청 이후 확정 원본 snapshot 이 변경됨");
        }
        return documentSourceRevisionRepository
                .findByProductDocument_IdAndSourceHash(snapshot.documentId(), snapshot.sourceHash())
                .filter(revision -> Objects.equals(revision.getProductDocumentId(), snapshot.documentId()))
                .orElseThrow(() -> invalidProviderResponse("확정 원본의 immutable source revision 이 없음"));
    }

    private List<AnchorPersistencePlan> planAnchors(
            Finding finding,
            FindingPayload payload,
            Job job,
            DocumentSourceRevision sourceRevision,
            Map<Long, RagRetrievedChunk> contextsByChunkId
    ) {
        List<AnchorPersistencePlan> anchors = new ArrayList<>();
        Map<Long, String> factsById = job.request().knownFacts().stream()
                .collect(Collectors.toMap(
                        AnalysisRequest.KnownFactPayload::factId,
                        AnalysisRequest.KnownFactPayload::text));
        for (Long factId : payload.knownFactIds()) {
            String excerpt = factsById.get(factId);
            Utf8Range range = uniqueUtf8RangeIfPresent(job.documentSnapshot().text(), excerpt);
            if (range != null) {
                anchors.add(AnchorPersistencePlan.documentClaim(
                        finding,
                        job.documentSnapshot().documentId(),
                        sourceRevision.getId(),
                        sourceRevision.getSourceHash(),
                        TEXT_LAYER_PAGE,
                        range,
                        excerpt));
            }
        }
        for (FindingPayload.EvidenceSpanPayload span : payload.evidenceSpans()) {
            RagRetrievedChunk context = contextsByChunkId.get(span.chunkId());
            if (context == null || !payload.retrievedContextChunkIds().contains(span.chunkId())) {
                throw invalidProviderResponse("인용하지 않은 근거 청크의 범위: " + span.chunkId());
            }
            uniqueUtf8Range(context.chunkText(), span.excerpt(), "retrieved chunk " + span.chunkId());
            EvidenceSourceSnapshot source = job.evidenceSources().get(context.evidenceDocumentId());
            if (source == null || source.evidenceDocumentId() != context.evidenceDocumentId()
                    || !Objects.equals(source.sourceHash(), context.sourceHash())) {
                throw invalidProviderResponse("검색 근거의 source/hash snapshot 이 일치하지 않음: "
                        + span.chunkId());
            }
            Utf8Range range = uniqueUtf8Range(
                    source.text(), span.excerpt(), "evidence source " + source.evidenceDocumentId());
            anchors.add(AnchorPersistencePlan.policyRequirement(
                    finding,
                    context.evidenceDocumentId(),
                    context.chunkId(),
                    context.sourceHash(),
                    TEXT_LAYER_PAGE,
                    range,
                    span.excerpt()));
        }
        return anchors;
    }

    private record Utf8Range(long start, long end) {
    }

    private Utf8Range uniqueUtf8RangeIfPresent(String source, String excerpt) {
        if (source == null || excerpt == null || excerpt.isEmpty()) {
            return null;
        }
        int startIndex = source.indexOf(excerpt);
        if (startIndex < 0 || source.indexOf(excerpt, startIndex + 1) >= 0) {
            return null;
        }
        long start = source.substring(0, startIndex).getBytes(StandardCharsets.UTF_8).length;
        return new Utf8Range(start, start + excerpt.getBytes(StandardCharsets.UTF_8).length);
    }

    private Utf8Range uniqueUtf8Range(String source, String excerpt, String sourceName) {
        Utf8Range range = uniqueUtf8RangeIfPresent(source, excerpt);
        if (range == null) {
            throw invalidProviderResponse(sourceName + " 에 exact excerpt 범위가 없거나 둘 이상임");
        }
        return range;
    }

    private String lineageId(String executionToken, int providerOrdinal) {
        byte[] digest = sha256Bytes(
                FINDING_LINEAGE_NAMESPACE + "\0" + executionToken + "\0" + providerOrdinal);
        digest[6] = (byte) ((digest[6] & 0x0f) | 0x80);
        digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
        long mostSignificantBits = 0;
        long leastSignificantBits = 0;
        for (int index = 0; index < 8; index++) {
            mostSignificantBits = (mostSignificantBits << 8) | (digest[index] & 0xffL);
            leastSignificantBits = (leastSignificantBits << 8) | (digest[index + 8] & 0xffL);
        }
        return new UUID(mostSignificantBits, leastSignificantBits).toString();
    }

    private static String normalizeEvidenceContent(String content) {
        return Objects.requireNonNull(content, "Evidence document content must not be null")
                .replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private static String sha256(String value) {
        return HexFormat.of().formatHex(sha256Bytes(value));
    }

    private static byte[] sha256Bytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void saveRagRun(AnalysisExecution execution, Job job) {
        AnalysisRagRun ragRun = AnalysisRagRun.create(
                execution,
                job.queryHash(),
                job.embeddingModel(),
                RETRIEVAL_VERSION,
                PgVectorEvidenceRetriever.TOP_K,
                job.retrievedAt());
        for (RagRetrievedChunk retrieved : job.retrievedChunks()) {
            ragRun.addSnapshot(findExactChunk(retrieved), retrieved.rank(), retrieved.similarity());
        }
        ragRunRepository.save(ragRun);
    }

    private EvidenceDocumentChunk findExactChunk(RagRetrievedChunk retrieved) {
        return evidenceChunkRepository
                .findAllByEvidenceDocumentIdAndSourceHashAndChunkingVersionAndEmbeddingModelOrderByChunkOrdinalAsc(
                        retrieved.evidenceDocumentId(),
                        retrieved.sourceHash(),
                        retrieved.chunkingVersion(),
                        retrieved.embeddingModel()).stream()
                .filter(chunk -> Objects.equals(chunk.getId(), retrieved.chunkId()))
                .filter(chunk -> chunk.getChunkOrdinal() == retrieved.chunkOrdinal())
                .filter(chunk -> Objects.equals(chunk.getChunkHash(), retrieved.chunkHash()))
                .filter(chunk -> Objects.equals(chunk.getChunkText(), retrieved.chunkText()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Provider에 전달한 근거 청크가 더 이상 일치하지 않습니다: " + retrieved.chunkId()));
    }

    private void markFailed(Long analysisId, ErrorCode errorCode, boolean retryable,
                            String fence, String traceId) {
        transactionTemplate.execute(status -> {
            Analysis analysis = findWithLock(analysisId);
            if (!isCurrent(analysis, fence)) {
                return false;
            }
            failAndAudit(
                    analysis,
                    findRunningExecution(analysisId, fence),
                    errorCode,
                    retryable,
                    traceId);
            analysisRepository.flush();
            return true;
        });
    }

    private void failAndAudit(
            Analysis analysis,
            AnalysisExecution execution,
            ErrorCode errorCode,
            boolean retryable,
            String traceId
    ) {
        execution.fail(errorCode.name(), retryable, OffsetDateTime.now(clock));
        analysis.fail(errorCode, retryable);
        appendTerminalAudit(traceId, AuditAction.ANALYSIS_FAILED, analysis.getId());
    }

    private AnalysisExecution findRunningExecution(Long analysisId, String fence) {
        AnalysisExecution execution = analysisExecutionRepository
                .findByAnalysisIdAndExecutionToken(analysisId, fence)
                .orElseThrow(() -> new IllegalStateException(
                        "분석 %d 실행 %s 가 존재하지 않습니다.".formatted(analysisId, fence)));
        if (!execution.isRunning()) {
            throw new IllegalStateException(
                    "분석 %d 실행 %s 이 RUNNING 상태가 아닙니다.".formatted(analysisId, fence));
        }
        return execution;
    }

    private void appendTerminalAudit(String traceId, AuditAction action, Long analysisId) {
        auditEventRepository.save(AuditEvent.create(
                Objects.requireNonNull(traceId, "audit writes require a server trace"),
                null,
                action,
                action.getResourceType(),
                analysisId,
                null,
                analysisId));
    }

    private static final class RagPreparationException extends RuntimeException {

        private RagPreparationException(RuntimeException cause) {
            super(cause);
        }
    }

    private Set<Long> personaIds(List<PersonaCode> codes, Map<PersonaCode, Long> personaIdsByCode) {
        return codes.stream()
                .map(personaIdsByCode::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Analysis find(Long analysisId) {
        return analysisRepository.findById(analysisId)
                .orElseThrow(() -> new IllegalStateException("분석 %d 가 존재하지 않습니다.".formatted(analysisId)));
    }

    private Analysis findWithLock(Long analysisId) {
        return analysisRepository.findWithLockById(analysisId)
                .orElseThrow(() -> new IllegalStateException("분석 %d 가 존재하지 않습니다.".formatted(analysisId)));
    }
}
