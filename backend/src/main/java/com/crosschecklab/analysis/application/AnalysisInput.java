package com.crosschecklab.analysis.application;

import com.crosschecklab.analysis.provider.dto.AnalysisRequest;
import com.crosschecklab.analysis.rag.RagRetrievedChunk;
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
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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

    // 확정 텍스트 + Pack + 정렬된 선택 항목 내용의 SHA-256. 내용이 같은 중복 분석을 DB UNIQUE 로 막는 지문이다.
    public String inputHash() {
        String canonical = String.join("|",
                document.getExtractedText().strip(),
                String.valueOf(redTeamPack.getId()),
                join(personaIds()),
                evidenceDocuments.stream()
                        .sorted(java.util.Comparator.comparing(EvidenceDocument::getId))
                        .map(this::evidenceContentIdentity)
                        .collect(Collectors.joining(",")),
                knownFacts.stream()
                        .sorted(java.util.Comparator.comparing(KnownFact::factId))
                        .map(fact -> fact.factId() + ":" + fact.value().length() + ":" + fact.value())
                        .collect(Collectors.joining(",")));
        return sha256(canonical);
    }

    public String retrievalQuery() {
        return String.join("\n",
                document.getExtractedText().strip(),
                "personas=" + personas.stream()
                        .map(persona -> persona.getCode().name())
                        .collect(Collectors.joining(",")),
                "rules=" + ruleCodes.stream()
                        .map(ruleCode -> ruleCode.name())
                        .collect(Collectors.joining(",")));
    }

    public String retrievalQueryHash() {
        return sha256(retrievalQuery());
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }

    private String evidenceContentIdentity(EvidenceDocument evidence) {
        return evidence.getId() + ":" + sha256(normalizeEvidenceContent(evidence.getContent()));
    }

    private String normalizeEvidenceContent(String content) {
        return java.util.Objects.requireNonNull(content, "근거 문서 내용은 필수입니다.")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .strip();
    }

    public AnalysisRequest toProviderRequest(Long analysisId, String scenarioCode,
                                             List<RagRetrievedChunk> retrievedChunks) {
        Map<Long, EvidenceDocument> evidenceById = evidenceDocuments.stream()
                .collect(Collectors.toMap(EvidenceDocument::getId, Function.identity()));
        return new AnalysisRequest(
                analysisId,
                scenarioCode,
                document.getExtractedText().strip(),
                personas.stream().map(PersonaTemplate::getCode).toList(),
                redTeamPack.getCode(),
                ruleCodes,
                evidenceDocumentIds().stream().sorted().toList(),
                retrievedChunks.stream()
                        .map(chunk -> toRetrievedContext(chunk, evidenceById))
                        .toList(),
                knownFacts.stream()
                        .map(fact -> new AnalysisRequest.KnownFactPayload(fact.factId(), fact.value()))
                        .toList());
    }

    private AnalysisRequest.RetrievedContextPayload toRetrievedContext(
            RagRetrievedChunk chunk, Map<Long, EvidenceDocument> evidenceById) {
        EvidenceDocument evidence = evidenceById.get(chunk.evidenceDocumentId());
        if (evidence == null) {
            throw new IllegalArgumentException(
                    "검색 결과에 선택하지 않은 근거 문서가 포함되어 있습니다: " + chunk.evidenceDocumentId());
        }
        return new AnalysisRequest.RetrievedContextPayload(
                chunk.chunkId(),
                chunk.evidenceDocumentId(),
                evidence.getSourceType(),
                evidence.getTitle(),
                chunk.chunkText(),
                chunk.rank(),
                chunk.similarity());
    }

    private String join(Set<Long> ids) {
        return ids.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }
}
