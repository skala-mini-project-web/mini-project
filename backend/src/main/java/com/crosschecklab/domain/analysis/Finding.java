package com.crosschecklab.domain.analysis;

import com.crosschecklab.global.common.BaseTimeEntity;
import com.crosschecklab.global.common.enums.Severity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    @Column(nullable = false, columnDefinition = "text")
    private String statement;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity severity;

    @Column(columnDefinition = "text")
    private String recommendation;

    @ElementCollection
    @CollectionTable(name = "finding_affected_personas", joinColumns = @JoinColumn(name = "finding_id"))
    @Column(name = "persona_template_id", nullable = false)
    private Set<Long> affectedPersonaTemplateIds = new LinkedHashSet<>();

    @OneToMany(mappedBy = "finding", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EvidenceReference> evidenceReferences = new ArrayList<>();

    public static Finding create(Long analysisId, String statement, Severity severity, String recommendation,
                                 Set<Long> affectedPersonaTemplateIds) {
        Finding finding = new Finding();
        finding.analysisId = analysisId;
        finding.statement = statement;
        finding.severity = severity;
        finding.recommendation = recommendation;
        finding.affectedPersonaTemplateIds = new LinkedHashSet<>(affectedPersonaTemplateIds);
        return finding;
    }

    public void addEvidenceReference(Long evidenceDocumentId, String excerpt) {
        evidenceReferences.add(EvidenceReference.of(this, evidenceDocumentId, excerpt));
    }
}
