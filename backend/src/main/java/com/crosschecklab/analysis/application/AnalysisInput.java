package com.crosschecklab.analysis.application;

import com.crosschecklab.analysis.provider.dto.AnalysisRequest;
import com.crosschecklab.domain.document.ProductDocument;
import com.crosschecklab.domain.evidence.EvidenceDocument;
import com.crosschecklab.domain.groundtruth.GroundTruthFact;
import com.crosschecklab.domain.persona.PersonaTemplate;
import com.crosschecklab.domain.redteam.RedTeamPack;
import com.crosschecklab.global.common.enums.RedTeamRuleCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// 검증을 통과한 분석 입력 묶음. 생성(해시 계산)과 실행(Provider 요청 조립) 양쪽에서 재사용한다.
public record AnalysisInput(
        ProductDocument document,
        List<PersonaTemplate> personas,
        List<EvidenceDocument> evidenceDocuments,
        RedTeamPack redTeamPack,
        List<RedTeamRuleCode> ruleCodes,
        List<KnownFact> knownFacts
) {

    public record KnownFact(Long factId, String label, String value, GroundTruthFact source) {
    }

    public Set<Long> personaIds() {
        return personas.stream().map(PersonaTemplate::getId).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public Set<Long> evidenceDocumentIds() {
        return evidenceDocuments.stream().map(EvidenceDocument::getId).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // 확정 텍스트 + Pack + 정렬된 선택 id 목록의 SHA-256. 내용이 같은 중복 분석을 DB UNIQUE 로 막는 지문이다.
    public String inputHash() {
        String canonical = String.join("|",
                document.getExtractedText().strip(),
                String.valueOf(redTeamPack.getId()),
                join(personaIds()),
                join(evidenceDocumentIds()),
                knownFacts.stream()
                        .sorted(java.util.Comparator.comparing(KnownFact::factId))
                        .map(fact -> fact.factId() + ":" + fact.value().length() + ":" + fact.value())
                        .collect(Collectors.joining(",")));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }

    public AnalysisRequest toProviderRequest(Long analysisId, String scenarioCode) {
        return new AnalysisRequest(
                analysisId,
                scenarioCode,
                document.getExtractedText().strip(),
                personas.stream().map(PersonaTemplate::getCode).toList(),
                redTeamPack.getCode(),
                ruleCodes,
                evidenceDocuments.stream()
                        .map(evidence -> new AnalysisRequest.EvidenceDocumentPayload(
                                evidence.getId(), evidence.getSourceType(), evidence.getTitle(), evidence.getContent()))
                        .toList(),
                knownFacts.stream()
                        .map(fact -> new AnalysisRequest.KnownFactPayload(fact.factId(), fact.value()))
                        .toList());
    }

    private String join(Set<Long> ids) {
        return ids.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }
}
