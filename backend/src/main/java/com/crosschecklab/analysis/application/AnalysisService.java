package com.crosschecklab.analysis.application;

import com.crosschecklab.analysis.provider.http.AiServiceProperties;
import com.crosschecklab.domain.analysis.Analysis;
import com.crosschecklab.domain.analysis.AnalysisRepository;
import com.crosschecklab.domain.analysis.EvidenceReference;
import com.crosschecklab.domain.analysis.Finding;
import com.crosschecklab.domain.analysis.FindingRepository;
import com.crosschecklab.domain.analysis.dto.AnalysisAcceptedResponse;
import com.crosschecklab.domain.analysis.dto.AnalysisCreateRequest;
import com.crosschecklab.domain.analysis.dto.AnalysisResultResponse;
import com.crosschecklab.domain.analysis.dto.AnalysisStatusResponse;
import com.crosschecklab.domain.document.ProductDocument;
import com.crosschecklab.domain.document.ProductDocumentRepository;
import com.crosschecklab.domain.evidence.EvidenceDocument;
import com.crosschecklab.domain.evidence.EvidenceDocumentRepository;
import com.crosschecklab.domain.persona.PersonaTemplate;
import com.crosschecklab.domain.persona.PersonaTemplateRepository;
import com.crosschecklab.global.common.enums.PersonaCode;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private final AnalysisRepository analysisRepository;
    private final FindingRepository findingRepository;
    private final ProductDocumentRepository productDocumentRepository;
    private final EvidenceDocumentRepository evidenceDocumentRepository;
    private final PersonaTemplateRepository personaTemplateRepository;
    private final AnalysisInputLoader inputLoader;
    private final ApplicationEventPublisher eventPublisher;
    private final AiServiceProperties aiServiceProperties;

    // ANA-001. 입력을 검증해 CREATED 로 저장하고 즉시 202 를 반환한다.
    @Transactional
    public AnalysisAcceptedResponse create(AnalysisCreateRequest request, String scenarioCode) {
        AnalysisInput input = inputLoader.load(request.productDocumentId(), request.redTeamPackId(),
                request.personaIds(), request.evidenceDocumentIds());

        Analysis analysis = Analysis.create(input.document().getId(), input.redTeamPack().getId(),
                input.personaIds(), input.evidenceDocumentIds(), input.inputHash());
        try {
            // 부분 UNIQUE 인덱스(product_document_id, input_hash) 위반을 지금 드러낸다.
            analysisRepository.saveAndFlush(analysis);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_ANALYSIS_REQUEST);
        }

        eventPublisher.publishEvent(new AnalysisRequestedEvent(analysis.getId(), resolveScenario(scenarioCode)));
        return AnalysisAcceptedResponse.from(analysis);
    }

    // ANA-002. 조회만 하며 상태를 바꾸지 않는다.
    @Transactional(readOnly = true)
    public AnalysisStatusResponse getStatus(Long analysisId) {
        return AnalysisStatusResponse.from(find(analysisId));
    }

    // ANA-003. 새 Analysis 를 만들지 않고 같은 행을 RUNNING 으로 되돌린다.
    @Transactional
    public AnalysisAcceptedResponse retry(Long analysisId, String scenarioCode) {
        Analysis analysis = find(analysisId);
        analysis.requireRetryable();
        analysis.markRunning();
        try {
            // FAILED 였던 행이 다시 활성화되면서 같은 입력의 다른 분석과 UNIQUE 로 부딪힐 수 있다.
            analysisRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_ANALYSIS_REQUEST);
        }

        eventPublisher.publishEvent(new AnalysisRequestedEvent(analysisId, resolveScenario(scenarioCode)));
        return AnalysisAcceptedResponse.from(analysis);
    }

    // ANA-004
    @Transactional(readOnly = true)
    public AnalysisResultResponse getResult(Long analysisId) {
        Analysis analysis = find(analysisId);
        analysis.requireCompleted();

        ProductDocument document = productDocumentRepository.findById(analysis.getProductDocumentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        List<Finding> findings = findingRepository.findByAnalysisIdOrderByIdAsc(analysisId);

        Map<Long, PersonaCode> personaCodes = personaTemplateRepository.findAll().stream()
                .collect(Collectors.toMap(PersonaTemplate::getId, PersonaTemplate::getCode));
        Map<Long, EvidenceDocument> evidenceDocuments = evidenceDocumentRepository
                .findAllById(referencedEvidenceIds(analysis, findings)).stream()
                .collect(Collectors.toMap(EvidenceDocument::getId, Function.identity()));

        return AnalysisResultResponse.of(analysis, document, findings, personaCodes, evidenceDocuments);
    }

    // Provider 가 선택 목록 밖의 근거를 인용했을 수도 있으므로 실제 인용된 id 까지 합쳐서 조회한다.
    private Set<Long> referencedEvidenceIds(Analysis analysis, List<Finding> findings) {
        Set<Long> ids = new LinkedHashSet<>(analysis.getEvidenceDocumentIds());
        findings.forEach(finding -> finding.getEvidenceReferences()
                .forEach(reference -> ids.add(reference.getEvidenceDocumentId())));
        return ids;
    }

    // X-Demo-Scenario 헤더가 없으면 설정의 기본 시나리오를 쓴다.
    private String resolveScenario(String scenarioCode) {
        return StringUtils.hasText(scenarioCode) ? scenarioCode.trim() : aiServiceProperties.defaultScenarioCode();
    }

    private Analysis find(Long analysisId) {
        return analysisRepository.findById(analysisId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
