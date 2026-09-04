package com.crosschecklab.analysis.application;

import com.crosschecklab.analysis.provider.ProviderException;
import com.crosschecklab.analysis.provider.RiskAnalysisProvider;
import com.crosschecklab.analysis.provider.dto.AnalysisRequest;
import com.crosschecklab.analysis.provider.dto.AnalysisResult;
import com.crosschecklab.analysis.provider.dto.FindingPayload;
import com.crosschecklab.domain.analysis.Analysis;
import com.crosschecklab.domain.analysis.AnalysisRepository;
import com.crosschecklab.domain.analysis.Finding;
import com.crosschecklab.domain.analysis.FindingRepository;
import com.crosschecklab.domain.audit.AuditAction;
import com.crosschecklab.domain.audit.AuditService;
import com.crosschecklab.domain.persona.PersonaTemplate;
import com.crosschecklab.domain.persona.PersonaTemplateRepository;
import com.crosschecklab.global.common.enums.PersonaCode;
import com.crosschecklab.global.config.AsyncConfig;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
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

    private final AnalysisRepository analysisRepository;
    private final FindingRepository findingRepository;
    private final PersonaTemplateRepository personaTemplateRepository;
    private final AnalysisInputLoader inputLoader;
    private final RiskAnalysisProvider provider;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public AnalysisJobService(AnalysisRepository analysisRepository, FindingRepository findingRepository,
                              PersonaTemplateRepository personaTemplateRepository, AnalysisInputLoader inputLoader,
                              RiskAnalysisProvider provider, AuditService auditService,
                              PlatformTransactionManager transactionManager, Clock clock) {
        this.analysisRepository = analysisRepository;
        this.findingRepository = findingRepository;
        this.personaTemplateRepository = personaTemplateRepository;
        this.inputLoader = inputLoader;
        this.provider = provider;
        this.auditService = auditService;
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
        Job job;
        try {
            job = transactionTemplate.execute(status -> startRunning(analysisId, scenarioCode));
        } catch (BusinessException e) {
            // 생성 이후 입력이 바뀐 경우(문서 확정 해제, 근거 비활성화 등) 원인 코드를 그대로 남긴다.
            log.warn("분석 {} 입력이 더 이상 유효하지 않음 errorCode={}", analysisId, e.getErrorCode());
            markFailed(analysisId, e.getErrorCode(), false, null, traceId);
            return;
        } catch (RuntimeException e) {
            log.error("분석 {} 입력 준비 실패", analysisId, e);
            markFailed(analysisId, ErrorCode.INTERNAL_ERROR, false, null, traceId);
            return;
        }

        try {
            AnalysisResult result = provider.analyze(job.request());
            validateKnownFactReferences(job.request(), result);
            transactionTemplate.executeWithoutResult(status -> saveResult(analysisId, result, job.fence(), traceId));
        } catch (ProviderException e) {
            log.warn("분석 {} 실패 errorCode={} retryable={}", analysisId, e.getErrorCode(), e.isRetryable());
            markFailed(analysisId, e.getErrorCode(), e.isRetryable(), job.fence(), traceId);
        } catch (AuditAppendException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("분석 {} 결과 저장 실패", analysisId, e);
            markFailed(analysisId, ErrorCode.INTERNAL_ERROR, false, job.fence(), traceId);
        }
    }

    // fence = 이 실행 회차의 updated_at. 재시도가 값을 바꾸면 이전 회차는 결과를 버린다.
    private record Job(AnalysisRequest request, OffsetDateTime fence) {
    }

    private Job startRunning(Long analysisId, String scenarioCode) {
        Analysis analysis = find(analysisId);
        analysis.markRunning();
        analysisRepository.flush();
        AnalysisRequest request = inputLoader.load(analysis).toProviderRequest(analysisId, scenarioCode);
        return new Job(request, analysisRepository.findUpdatedAtById(analysisId).orElseThrow());
    }

    private void validateKnownFactReferences(AnalysisRequest request, AnalysisResult result) {
        Set<Long> acceptedFactIds = request.knownFacts() == null ? Set.of() : request.knownFacts().stream()
                .map(AnalysisRequest.KnownFactPayload::factId)
                .collect(Collectors.toSet());

        for (FindingPayload finding : result.findings()) {
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
    private boolean isCurrent(Analysis analysis, OffsetDateTime fence) {
        if (fence == null) {
            return true;
        }
        boolean current = fence.isEqual(analysis.getUpdatedAt());
        if (!current) {
            log.warn("분석 {} 이전 회차 결과를 버린다 (재시도가 이미 시작됨)", analysis.getId());
        }
        return current;
    }

    private void saveResult(Long analysisId, AnalysisResult result, OffsetDateTime fence, String traceId) {
        Analysis analysis = findWithLock(analysisId);
        if (!isCurrent(analysis, fence)) {
            return;
        }
        // 재시도면 이전 회차 Finding 을 먼저 비운다 (연관 행은 FK CASCADE).
        findingRepository.deleteByAnalysisId(analysisId);
        findingRepository.flush();

        Map<PersonaCode, Long> personaIdsByCode = personaTemplateRepository.findAll().stream()
                .collect(Collectors.toMap(PersonaTemplate::getCode, PersonaTemplate::getId));

        for (FindingPayload payload : result.findings()) {
            Finding finding = Finding.create(analysisId, payload.statement(), payload.severity(),
                    payload.recommendation(), personaIds(payload.affectedPersonaCodes(), personaIdsByCode));
            payload.evidenceReferences()
                    .forEach(reference -> finding.addEvidenceReference(reference.evidenceDocumentId(), reference.excerpt()));
            findingRepository.save(finding);
        }
        analysis.complete(result.riskScore(), result.modelVersion(), result.promptVersion(), OffsetDateTime.now(clock));
        analysisRepository.flush();
        appendTerminalAudit(traceId, AuditAction.ANALYSIS_COMPLETED, analysisId);
    }

    private void markFailed(Long analysisId, ErrorCode errorCode, boolean retryable,
                            OffsetDateTime fence, String traceId) {
        transactionTemplate.executeWithoutResult(status -> {
            Analysis analysis = findWithLock(analysisId);
            if (!isCurrent(analysis, fence)) {
                return;
            }
            analysis.fail(errorCode, retryable);
            analysisRepository.flush();
            appendTerminalAudit(traceId, AuditAction.ANALYSIS_FAILED, analysisId);
        });
    }

    private void appendTerminalAudit(String traceId, AuditAction action, Long analysisId) {
        try {
            auditService.appendWithTrace(traceId, null, action, analysisId, null, analysisId);
        } catch (RuntimeException e) {
            throw new AuditAppendException(e);
        }
    }

    private static final class AuditAppendException extends RuntimeException {

        private AuditAppendException(RuntimeException cause) {
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
