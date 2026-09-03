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
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public AnalysisJobService(AnalysisRepository analysisRepository, FindingRepository findingRepository,
                              PersonaTemplateRepository personaTemplateRepository, AnalysisInputLoader inputLoader,
                              RiskAnalysisProvider provider, PlatformTransactionManager transactionManager,
                              Clock clock) {
        this.analysisRepository = analysisRepository;
        this.findingRepository = findingRepository;
        this.personaTemplateRepository = personaTemplateRepository;
        this.inputLoader = inputLoader;
        this.provider = provider;
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
        run(event.analysisId(), event.scenarioCode());
    }

    private void run(Long analysisId, String scenarioCode) {
        AnalysisRequest request;
        try {
            request = transactionTemplate.execute(status -> startRunning(analysisId, scenarioCode));
        } catch (BusinessException e) {
            // 생성 이후 입력이 바뀐 경우(문서 확정 해제, 근거 비활성화 등) 원인 코드를 그대로 남긴다.
            log.warn("분석 {} 입력이 더 이상 유효하지 않음 errorCode={}", analysisId, e.getErrorCode());
            markFailed(analysisId, e.getErrorCode(), false);
            return;
        } catch (RuntimeException e) {
            log.error("분석 {} 입력 준비 실패", analysisId, e);
            markFailed(analysisId, ErrorCode.INTERNAL_ERROR, false);
            return;
        }

        try {
            AnalysisResult result = provider.analyze(request);
            transactionTemplate.executeWithoutResult(status -> saveResult(analysisId, result));
        } catch (ProviderException e) {
            log.warn("분석 {} 실패 errorCode={} retryable={}", analysisId, e.getErrorCode(), e.isRetryable());
            markFailed(analysisId, e.getErrorCode(), e.isRetryable());
        } catch (RuntimeException e) {
            log.error("분석 {} 결과 저장 실패", analysisId, e);
            markFailed(analysisId, ErrorCode.INTERNAL_ERROR, false);
        }
    }

    private AnalysisRequest startRunning(Long analysisId, String scenarioCode) {
        Analysis analysis = find(analysisId);
        analysis.markRunning();
        return inputLoader.load(analysis).toProviderRequest(analysisId, scenarioCode);
    }

    private void saveResult(Long analysisId, AnalysisResult result) {
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
        Analysis analysis = find(analysisId);
        analysis.complete(result.riskScore(), result.modelVersion(), result.promptVersion(), OffsetDateTime.now(clock));
    }

    private void markFailed(Long analysisId, ErrorCode errorCode, boolean retryable) {
        transactionTemplate.executeWithoutResult(status -> find(analysisId).fail(errorCode, retryable));
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
}
