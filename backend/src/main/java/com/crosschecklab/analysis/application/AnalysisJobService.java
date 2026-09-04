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
import com.crosschecklab.domain.analysis.AnalysisRagRun;
import com.crosschecklab.domain.analysis.AnalysisRagRunRepository;
import com.crosschecklab.domain.analysis.AnalysisRepository;
import com.crosschecklab.domain.analysis.Finding;
import com.crosschecklab.domain.analysis.FindingRepository;
import com.crosschecklab.domain.audit.AuditAction;
import com.crosschecklab.domain.audit.AuditEvent;
import com.crosschecklab.domain.audit.AuditEventRepository;
import com.crosschecklab.domain.evidence.EvidenceDocumentChunk;
import com.crosschecklab.domain.evidence.EvidenceDocumentChunkRepository;
import com.crosschecklab.domain.persona.PersonaTemplate;
import com.crosschecklab.domain.persona.PersonaTemplateRepository;
import com.crosschecklab.global.common.enums.PersonaCode;
import com.crosschecklab.global.config.AsyncConfig;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
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

    private final AnalysisRepository analysisRepository;
    private final EntityManager entityManager;
    private final FindingRepository findingRepository;
    private final AnalysisRagRunRepository ragRunRepository;
    private final EvidenceDocumentChunkRepository evidenceChunkRepository;
    private final PersonaTemplateRepository personaTemplateRepository;
    private final AnalysisInputLoader inputLoader;
    private final EvidenceChunkIndexer evidenceChunkIndexer;
    private final PgVectorEvidenceRetriever evidenceRetriever;
    private final RiskAnalysisProvider provider;
    private final AuditEventRepository auditEventRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public AnalysisJobService(AnalysisRepository analysisRepository, EntityManager entityManager,
                              FindingRepository findingRepository,
                              AnalysisRagRunRepository ragRunRepository,
                              EvidenceDocumentChunkRepository evidenceChunkRepository,
                              PersonaTemplateRepository personaTemplateRepository, AnalysisInputLoader inputLoader,
                              EvidenceChunkIndexer evidenceChunkIndexer,
                              PgVectorEvidenceRetriever evidenceRetriever,
                              RiskAnalysisProvider provider, AuditEventRepository auditEventRepository,
                              PlatformTransactionManager transactionManager, Clock clock) {
        this.analysisRepository = analysisRepository;
        this.entityManager = entityManager;
        this.findingRepository = findingRepository;
        this.ragRunRepository = ragRunRepository;
        this.evidenceChunkRepository = evidenceChunkRepository;
        this.personaTemplateRepository = personaTemplateRepository;
        this.inputLoader = inputLoader;
        this.evidenceChunkIndexer = evidenceChunkIndexer;
        this.evidenceRetriever = evidenceRetriever;
        this.provider = provider;
        this.auditEventRepository = auditEventRepository;
        this.clock = clock;
        // REQUIRES_NEW: 이 작업의 상태 전이는 요청 트랜잭션과 완전히 독립적으로 커밋되어야 한다.
        // (AFTER_COMMIT 콜백 안에서는 이미 완료된 트랜잭션에 합류해 커밋이 유실될 수 있다)
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // CREATED/RUNNING 행이 커밋된 뒤에 시작해야 작업 스레드가 해당 행을 읽을 수 있다.
    @Async(AsyncConfig.ANALYSIS_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(AnalysisRequestedEvent event) {
        run(event.analysisId(), event.scenarioCode(), event.traceId());
    }

    private void run(Long analysisId, String scenarioCode, String traceId) {
        String fence = transactionTemplate.execute(status -> beginExecution(analysisId));
        Job job;
        try {
            job = transactionTemplate.execute(status -> prepareJob(analysisId, scenarioCode, fence));
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
            transactionTemplate.execute(
                    status -> saveResult(analysisId, result, job, traceId));
        } catch (ProviderException e) {
            log.warn("분석 {} 실패 errorCode={} retryable={}", analysisId, e.getErrorCode(), e.isRetryable());
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
            OffsetDateTime retrievedAt
    ) {
    }

    private String beginExecution(Long analysisId) {
        Analysis analysis = findWithLock(analysisId);
        analysis.markRunning();
        analysisRepository.flush();
        return analysis.getExecutionToken();
    }

    private Job prepareJob(Long analysisId, String scenarioCode, String fence) {
        Analysis analysis = find(analysisId);
        if (!isCurrent(analysis, fence)) {
            return null;
        }
        AnalysisInput input = inputLoader.load(analysis);
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
                OffsetDateTime.now(clock));
        Analysis currentAnalysis = findWithLock(analysisId);
        entityManager.refresh(currentAnalysis);
        if (!isCurrent(currentAnalysis, fence)) {
            return null;
        }
        saveRagRun(currentAnalysis, job);
        return job;
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
        Set<Long> acceptedChunkIds = request.retrievedContexts() == null ? Set.of()
                : request.retrievedContexts().stream()
                        .map(AnalysisRequest.RetrievedContextPayload::chunkId)
                        .collect(Collectors.toSet());
        Set<Long> acceptedFactIds = request.knownFacts() == null ? Set.of() : request.knownFacts().stream()
                .map(AnalysisRequest.KnownFactPayload::factId)
                .collect(Collectors.toSet());

        for (FindingPayload finding : result.findings()) {
            if (finding.retrievedContextChunkIds() == null) {
                throw invalidProviderResponse("finding 에 retrievedContextChunkIds 가 없음");
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
        }
    }

    private ProviderException invalidProviderResponse(String detail) {
        return new ProviderException(ErrorCode.PROVIDER_RESPONSE_INVALID, false, detail);
    }

    // 잠근 행을 기준으로 이 회차가 아직 유효한가. 그 사이 재시도가 시작됐으면 false.
    private boolean isCurrent(Analysis analysis, String fence) {
        if (fence == null) {
            return true;
        }
        boolean current = fence.equals(analysis.getExecutionToken());
        if (!current) {
            log.warn("분석 {} 이전 회차 결과를 버린다 (재시도가 이미 시작됨)", analysis.getId());
        }
        return current;
    }

    private boolean saveResult(Long analysisId, AnalysisResult result, Job job, String traceId) {
        Analysis analysis = findWithLock(analysisId);
        if (!isCurrent(analysis, job.fence())) {
            return false;
        }
        // 재시도면 이전 회차 Finding 을 먼저 비운다 (연관 행은 FK CASCADE).
        findingRepository.deleteByAnalysisId(analysisId);
        findingRepository.flush();

        Map<PersonaCode, Long> personaIdsByCode = personaTemplateRepository.findAll().stream()
                .collect(Collectors.toMap(PersonaTemplate::getCode, PersonaTemplate::getId));
        Map<Long, RagRetrievedChunk> contextsByChunkId =
                job.retrievedChunks().stream()
                        .collect(Collectors.toMap(
                                RagRetrievedChunk::chunkId,
                                context -> context));

        for (FindingPayload payload : result.findings()) {
            Finding finding = Finding.create(analysisId, payload.statement(), payload.severity(),
                    payload.recommendation(), personaIds(payload.affectedPersonaCodes(), personaIdsByCode));
            payload.retrievedContextChunkIds().stream()
                    .map(contextsByChunkId::get)
                    .forEach(context -> finding.addEvidenceReference(
                            context.evidenceDocumentId(), context.chunkText()));
            findingRepository.save(finding);
        }
        analysis.complete(result.riskScore(), result.modelVersion(), result.promptVersion(), OffsetDateTime.now(clock));
        appendTerminalAudit(traceId, AuditAction.ANALYSIS_COMPLETED, analysisId);
        analysisRepository.flush();
        return true;
    }

    private void saveRagRun(Analysis analysis, Job job) {
        ragRunRepository.deleteByAnalysisId(analysis.getId());
        AnalysisRagRun ragRun = AnalysisRagRun.create(
                analysis,
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
            analysis.fail(errorCode, retryable);
            appendTerminalAudit(traceId, AuditAction.ANALYSIS_FAILED, analysisId);
            analysisRepository.flush();
            return true;
        });
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
