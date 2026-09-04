package com.crosschecklab.domain.analysis.dto;

import com.crosschecklab.domain.analysis.Analysis;
import com.crosschecklab.domain.analysis.AnalysisGroundTruthFactSnapshot;
import com.crosschecklab.domain.analysis.AnalysisRagRetrievalSnapshot;
import com.crosschecklab.domain.analysis.AnalysisRagRun;
import com.crosschecklab.domain.analysis.Finding;
import com.crosschecklab.domain.document.ProductDocument;
import com.crosschecklab.domain.evidence.EvidenceDocument;
import com.crosschecklab.global.common.enums.AnalysisStatus;
import com.crosschecklab.global.common.enums.EvidenceSourceType;
import com.crosschecklab.global.common.enums.PersonaCode;
import com.crosschecklab.global.common.enums.Severity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// ANA-004. 저장된 id 를 코드·제목으로 되살려 내려준다.
public record AnalysisResultResponse(
        Long analysisId,
        AnalysisStatus status,
        Integer riskScore,
        SourceDocument sourceDocument,
        List<GroundingDocument> groundingDocuments,
        RetrievalTrace retrievalTrace,
        List<GroundTruthFactView> groundTruthFacts,
        List<FindingView> findings
) {

    public record SourceDocument(Long documentId, String fileName) {
    }

    public record GroundingDocument(Long documentId, EvidenceSourceType sourceType, String title) {
    }

    public record RetrievalTrace(
            String queryHash,
            String retrievalVersion,
            String embeddingModel,
            OffsetDateTime retrievedAt,
            List<RetrievedContextView> contexts
    ) {
    }

    public record RetrievedContextView(
            Long chunkId,
            Long evidenceDocumentId,
            EvidenceSourceType sourceType,
            String title,
            int rank,
            double similarity,
            String excerpt
    ) {
    }

    public record GroundTruthFactView(Long factId, String label, String value) {
    }

    public record FindingView(
            Long findingId,
            String statement,
            Severity severity,
            List<PersonaCode> affectedPersonaCodes,
            List<EvidenceReferenceView> evidenceReferences,
            String recommendation
    ) {
    }

    public record EvidenceReferenceView(Long evidenceDocumentId, EvidenceSourceType sourceType, String excerpt) {
    }

    public static AnalysisResultResponse of(Analysis analysis, ProductDocument document,
                                            List<AnalysisGroundTruthFactSnapshot> groundTruthFacts,
                                            List<Finding> findings,
                                            Map<Long, PersonaCode> personaCodes,
                                            Map<Long, EvidenceDocument> evidenceDocuments,
                                            AnalysisRagRun ragRun) {
        return new AnalysisResultResponse(
                analysis.getId(),
                analysis.getStatus(),
                analysis.getRiskScore(),
                new SourceDocument(document.getId(), document.getFileName()),
                analysis.getEvidenceDocumentIds().stream()
                        .map(evidenceDocuments::get).filter(Objects::nonNull)
                        .map(evidence -> new GroundingDocument(
                                evidence.getId(), evidence.getSourceType(), evidence.getTitle()))
                        .toList(),
                toRetrievalTrace(ragRun, evidenceDocuments),
                groundTruthFacts.stream()
                        .map(fact -> new GroundTruthFactView(
                                fact.getGroundTruthFact().getId(), fact.getLabel(), fact.getValue()))
                        .toList(),
                findings.stream().map(finding -> toView(finding, personaCodes, evidenceDocuments)).toList());
    }

    private static RetrievalTrace toRetrievalTrace(
            AnalysisRagRun ragRun, Map<Long, EvidenceDocument> evidenceDocuments) {
        return new RetrievalTrace(
                ragRun.getQueryHash(),
                ragRun.getRetrievalVersion(),
                ragRun.getEmbeddingModel(),
                ragRun.getRetrievedAt(),
                ragRun.getSnapshots().stream()
                        .map(snapshot -> toRetrievedContext(snapshot, evidenceDocuments))
                        .toList());
    }

    private static RetrievedContextView toRetrievedContext(
            AnalysisRagRetrievalSnapshot snapshot,
            Map<Long, EvidenceDocument> evidenceDocuments) {
        Long evidenceDocumentId = snapshot.getEvidenceDocument().getId();
        EvidenceDocument evidence = evidenceDocuments.get(evidenceDocumentId);
        return new RetrievedContextView(
                snapshot.getEvidenceDocumentChunk().getId(),
                evidenceDocumentId,
                evidence == null ? snapshot.getEvidenceDocument().getSourceType() : evidence.getSourceType(),
                evidence == null ? snapshot.getEvidenceDocument().getTitle() : evidence.getTitle(),
                snapshot.getRank(),
                snapshot.getSimilarity(),
                snapshot.getChunkText());
    }

    private static FindingView toView(Finding finding, Map<Long, PersonaCode> personaCodes,
                                      Map<Long, EvidenceDocument> evidenceDocuments) {
        return new FindingView(
                finding.getId(),
                finding.getStatement(),
                finding.getSeverity(),
                finding.getAffectedPersonaTemplateIds().stream()
                        .map(personaCodes::get).filter(Objects::nonNull).toList(),
                finding.getEvidenceReferences().stream()
                        .map(reference -> new EvidenceReferenceView(
                                reference.getEvidenceDocumentId(),
                                sourceTypeOf(evidenceDocuments, reference.getEvidenceDocumentId()),
                                reference.getExcerpt()))
                        .toList(),
                finding.getRecommendation());
    }

    private static EvidenceSourceType sourceTypeOf(Map<Long, EvidenceDocument> evidenceDocuments, Long id) {
        EvidenceDocument evidence = evidenceDocuments.get(id);
        return evidence == null ? null : evidence.getSourceType();
    }
}
