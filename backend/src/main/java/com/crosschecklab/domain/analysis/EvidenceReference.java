package com.crosschecklab.domain.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

// Finding 이 인용한 근거 문서 발췌. updated_at 컬럼이 없어 BaseTimeEntity 를 쓰지 않는다.
@Entity
@Getter
@Table(name = "evidence_references")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EvidenceReference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "finding_id", nullable = false, updatable = false)
    private Finding finding;

    @Column(name = "evidence_document_id", nullable = false)
    private Long evidenceDocumentId;

    @Column(columnDefinition = "text")
    private String excerpt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    static EvidenceReference of(Finding finding, Long evidenceDocumentId, String excerpt) {
        EvidenceReference reference = new EvidenceReference();
        reference.finding = finding;
        reference.evidenceDocumentId = evidenceDocumentId;
        reference.excerpt = excerpt;
        return reference;
    }
}
