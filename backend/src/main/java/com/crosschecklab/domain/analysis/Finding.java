package com.crosschecklab.domain.analysis;

import com.crosschecklab.global.common.BaseTimeEntity;
import com.crosschecklab.global.common.enums.RedTeamRuleCode;
import com.crosschecklab.global.common.enums.Severity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 분석이 찾아낸 개별 위험 지적. 영향 Persona 는 조인 테이블, 근거 인용은 evidence_references 로 저장한다.
@Entity
@Getter
@Table(name = "findings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Finding extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "analysis_id", nullable = false, updatable = false)
    private Long analysisId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "analysis_execution_id", updatable = false)
    private AnalysisExecution analysisExecution;

    @Column(name = "lineage_id", updatable = false, length = 36)
    private String lineageId;

    @Column(name = "revision_number", updatable = false)
    private Integer revisionNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supersedes_finding_id", updatable = false)
    private Finding supersedesFinding;

    @Column(nullable = false, columnDefinition = "text")
    private String statement;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_rule_code", updatable = false, length = 40)
    private RedTeamRuleCode policyRuleCode;

    @Column(columnDefinition = "text")
    private String recommendation;

    @ElementCollection
    @CollectionTable(name = "finding_affected_personas", joinColumns = @JoinColumn(name = "finding_id"))
    @Column(name = "persona_template_id", nullable = false)
    private Set<Long> affectedPersonaTemplateIds = new LinkedHashSet<>();

    @OneToMany(mappedBy = "finding", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EvidenceReference> evidenceReferences = new ArrayList<>();

    @OneToMany(mappedBy = "finding")
    private List<FindingEvidenceAnchor> evidenceAnchors = new ArrayList<>();

    public static Finding create(
            AnalysisExecution analysisExecution,
            String lineageId,
            int revisionNumber,
            Finding supersedesFinding,
            String statement,
            Severity severity,
            RedTeamRuleCode policyRuleCode,
            String recommendation,
            Set<Long> affectedPersonaTemplateIds
    ) {
        requirePersistedExecution(analysisExecution);
        String canonicalLineageId = requireCanonicalUuid(lineageId);
        requireRevisionIdentity(
                analysisExecution,
                canonicalLineageId,
                revisionNumber,
                supersedesFinding);
        requireNonBlank(statement, "statement");
        requireNonNull(severity, "severity");
        requireNonNull(policyRuleCode, "policyRuleCode");
        requireNonNull(affectedPersonaTemplateIds, "affectedPersonaTemplateIds");
        if (affectedPersonaTemplateIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("affectedPersonaTemplateIds must contain only positive IDs");
        }

        Finding finding = new Finding();
        finding.analysisId = analysisExecution.getAnalysis().getId();
        finding.analysisExecution = analysisExecution;
        finding.lineageId = canonicalLineageId;
        finding.revisionNumber = revisionNumber;
        finding.supersedesFinding = supersedesFinding;
        finding.statement = statement;
        finding.severity = severity;
        finding.policyRuleCode = policyRuleCode;
        finding.recommendation = recommendation;
        finding.affectedPersonaTemplateIds = new LinkedHashSet<>(affectedPersonaTemplateIds);
        return finding;
    }

    public void addEvidenceReference(Long evidenceDocumentId, String excerpt) {
        evidenceReferences.add(EvidenceReference.of(this, evidenceDocumentId, excerpt));
    }

    private static void requirePersistedExecution(AnalysisExecution execution) {
        requireNonNull(execution, "analysisExecution");
        if (execution.getId() == null) {
            throw new IllegalArgumentException("analysisExecution must be persisted");
        }
        if (execution.getAnalysis() == null || execution.getAnalysis().getId() == null) {
            throw new IllegalArgumentException("analysisExecution must belong to a persisted analysis");
        }
    }

    private static String requireCanonicalUuid(String value) {
        requireNonBlank(value, "lineageId");
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equals(value)) {
                throw new IllegalArgumentException("lineageId must be a canonical lowercase UUID");
            }
            return canonical;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("lineageId must be a canonical lowercase UUID", exception);
        }
    }

    private static void requireRevisionIdentity(
            AnalysisExecution execution,
            String lineageId,
            int revisionNumber,
            Finding supersedesFinding
    ) {
        if (revisionNumber <= 0) {
            throw new IllegalArgumentException("revisionNumber must be positive");
        }
        if (revisionNumber == 1) {
            if (supersedesFinding != null) {
                throw new IllegalArgumentException("the initial revision must not supersede a finding");
            }
            return;
        }
        if (supersedesFinding == null || supersedesFinding.getId() == null) {
            throw new IllegalArgumentException("a later revision must supersede a persisted finding");
        }
        if (!lineageId.equals(supersedesFinding.getLineageId())) {
            throw new IllegalArgumentException("a revision must preserve its finding lineage");
        }
        if (supersedesFinding.getRevisionNumber() == null
                || revisionNumber != supersedesFinding.getRevisionNumber() + 1) {
            throw new IllegalArgumentException("revisionNumber must immediately follow the superseded finding");
        }
        if (!execution.getAnalysis().getId().equals(supersedesFinding.getAnalysisId())) {
            throw new IllegalArgumentException("a revision must belong to the same analysis");
        }
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private static void requireNonNull(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
    }
}
