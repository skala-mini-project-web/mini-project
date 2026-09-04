package com.crosschecklab.analysis.application;

import com.crosschecklab.domain.analysis.Analysis;
import com.crosschecklab.domain.analysis.AnalysisGroundTruthFactSnapshot;
import com.crosschecklab.domain.analysis.AnalysisGroundTruthFactSnapshotRepository;
import com.crosschecklab.domain.document.ProductDocument;
import com.crosschecklab.domain.document.ProductDocumentRepository;
import com.crosschecklab.domain.evidence.EvidenceDocument;
import com.crosschecklab.domain.evidence.EvidenceDocumentRepository;
import com.crosschecklab.domain.groundtruth.GroundTruthFact.VerificationStatus;
import com.crosschecklab.domain.groundtruth.GroundTruthFactRepository;
import com.crosschecklab.domain.persona.PersonaTemplate;
import com.crosschecklab.domain.persona.PersonaTemplateRepository;
import com.crosschecklab.domain.redteam.RedTeamPack;
import com.crosschecklab.domain.redteam.RedTeamPackRepository;
import com.crosschecklab.domain.redteam.RedTeamRule;
import com.crosschecklab.global.common.enums.ExtractStatus;
import com.crosschecklab.global.common.enums.RedTeamRuleCode;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import com.crosschecklab.global.error.ErrorResponse;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 분석 입력 조회·검증 (ANA-001 선행 조건).
// 생성 시점과 실행 시점 모두 여기를 통해 같은 규칙으로 입력을 만든다.
@Component
@RequiredArgsConstructor
public class AnalysisInputLoader {

    private static final int MIN_SELECTION = 1;
    private static final int MAX_SELECTION = 3;

    private final ProductDocumentRepository productDocumentRepository;
    private final PersonaTemplateRepository personaTemplateRepository;
    private final EvidenceDocumentRepository evidenceDocumentRepository;
    private final RedTeamPackRepository redTeamPackRepository;
    private final GroundTruthFactRepository groundTruthFactRepository;
    private final AnalysisGroundTruthFactSnapshotRepository factSnapshotRepository;

    public AnalysisInput load(Long productDocumentId, Long redTeamPackId,
                              Collection<Long> personaIds, Collection<Long> evidenceDocumentIds) {
        return load(lockDocument(productDocumentId), redTeamPackId, personaIds, evidenceDocumentIds);
    }

    public ProductDocument lockDocument(Long productDocumentId) {
        return productDocumentRepository.findByIdForUpdate(productDocumentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    public AnalysisInput load(ProductDocument document, Long redTeamPackId,
                              Collection<Long> personaIds, Collection<Long> evidenceDocumentIds) {
        // 순서 = 오류 우선순위. 문서 상태(409)를 선택 값 오류(400)보다 먼저 판정한다.
        requireConfirmedDocument(document);
        List<PersonaTemplate> personas = loadPersonas(personaIds);
        List<EvidenceDocument> evidence = loadEvidenceDocuments(evidenceDocumentIds);
        RedTeamPack pack = loadPack(redTeamPackId);
        List<AnalysisInput.KnownFact> knownFacts = groundTruthFactRepository
                .findAllByDocumentIdAndVerificationStatusOrderByIdAsc(
                        document.getId(), VerificationStatus.VERIFIED).stream()
                .map(fact -> new AnalysisInput.KnownFact(
                        fact.getId(), fact.getLabel(), fact.getValue(), fact))
                .toList();
        return build(document, personas, evidence, pack, knownFacts);
    }

    // 실행·재시도 시점: 이미 저장된 선택을 그대로 다시 읽는다.
    public AnalysisInput load(Analysis analysis) {
        ProductDocument document = loadDocument(analysis.getProductDocumentId());
        List<PersonaTemplate> personas = loadPersonas(analysis.getPersonaTemplateIds());
        List<EvidenceDocument> evidence = loadEvidenceDocuments(analysis.getEvidenceDocumentIds());
        RedTeamPack pack = loadPack(analysis.getRedTeamPackId());
        List<AnalysisInput.KnownFact> knownFacts = factSnapshotRepository
                .findAllByAnalysisIdOrderByIdAsc(analysis.getId()).stream()
                .map(this::toKnownFact)
                .toList();
        return build(document, personas, evidence, pack, knownFacts);
    }

    private AnalysisInput build(ProductDocument document, List<PersonaTemplate> personas,
                                List<EvidenceDocument> evidence, RedTeamPack pack,
                                List<AnalysisInput.KnownFact> knownFacts) {
        return new AnalysisInput(document, personas, evidence, pack, loadRuleCodes(pack), knownFacts);
    }

    private AnalysisInput.KnownFact toKnownFact(AnalysisGroundTruthFactSnapshot snapshot) {
        return new AnalysisInput.KnownFact(
                snapshot.getGroundTruthFact().getId(), snapshot.getLabel(), snapshot.getValue(), null);
    }

    private ProductDocument loadDocument(Long id) {
        ProductDocument document = productDocumentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        requireConfirmedDocument(document);
        return document;
    }

    private void requireConfirmedDocument(ProductDocument document) {
        // 확정된 추출 텍스트만 분석 입력으로 쓴다.
        if (!document.isConfirmed() || document.getExtractStatus() != ExtractStatus.READY
                || document.getExtractedText() == null || document.getExtractedText().isBlank()) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_CONFIRMED);
        }
    }

    private List<PersonaTemplate> loadPersonas(Collection<Long> ids) {
        Set<Long> selected = requireSelectionCount(ids);
        List<PersonaTemplate> personas = personaTemplateRepository.findAllById(selected).stream()
                .sorted(Comparator.comparing(PersonaTemplate::getId)).toList();
        if (personas.size() != selected.size() || personas.stream().anyMatch(persona -> !persona.isActive())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    List.of(new ErrorResponse.FieldError("personaIds", "사용할 수 없는 Persona 가 포함되어 있습니다.")));
        }
        return personas;
    }

    private List<EvidenceDocument> loadEvidenceDocuments(Collection<Long> ids) {
        Set<Long> selected = requireSelectionCount(ids);
        List<EvidenceDocument> documents = evidenceDocumentRepository.findAllById(selected).stream()
                .sorted(Comparator.comparing(EvidenceDocument::getId)).toList();
        if (documents.size() != selected.size() || documents.stream().anyMatch(document -> !document.isActive())) {
            throw new BusinessException(ErrorCode.INVALID_EVIDENCE_DOCUMENT);
        }
        return documents;
    }

    private RedTeamPack loadPack(Long id) {
        RedTeamPack pack = redTeamPackRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!pack.isActive()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    List.of(new ErrorResponse.FieldError("redTeamPackId", "사용할 수 없는 Red Team Pack 입니다.")));
        }
        return pack;
    }

    private List<RedTeamRuleCode> loadRuleCodes(RedTeamPack pack) {
        // RedTeamPack 이 rules 를 @OrderBy 로 들고 있어 별도 조회가 필요 없다.
        List<RedTeamRuleCode> ruleCodes = pack.getRules().stream()
                .filter(RedTeamRule::isActive).map(RedTeamRule::getCode).toList();
        if (ruleCodes.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    List.of(new ErrorResponse.FieldError("redTeamPackId", "Pack 에 활성 규칙이 없습니다.")));
        }
        return ruleCodes;
    }

    // 중복 제거 후 1~3개
    private Set<Long> requireSelectionCount(Collection<Long> ids) {
        Set<Long> distinct = ids == null ? Set.of() : new LinkedHashSet<>(ids);
        if (distinct.size() < MIN_SELECTION || distinct.size() > MAX_SELECTION) {
            throw new BusinessException(ErrorCode.INVALID_SELECTION_COUNT);
        }
        return distinct;
    }
}
