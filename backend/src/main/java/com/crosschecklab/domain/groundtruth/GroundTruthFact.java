package com.crosschecklab.domain.groundtruth;

import com.crosschecklab.domain.document.ProductDocument;
import com.crosschecklab.domain.user.User;
import com.crosschecklab.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 사람이 확인한 문서 원문 전체를 공식 사실 한 건으로 보존한다.
// document_id UNIQUE 제약으로 문서마다 현재 스냅샷은 하나뿐이다.
@Entity
@Getter
@Table(name = "ground_truth_facts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroundTruthFact extends BaseTimeEntity {

    public enum Importance {
        HIGH
    }

    public enum VerificationStatus {
        CANDIDATE,
        VERIFIED,
        REJECTED
    }

    public enum ExtractionSource {
        CONFIRMED_DOCUMENT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false, unique = true, updatable = false)
    private Long documentId;

    @Column(nullable = false)
    private String label;

    // 요약이나 AI 추출값이 아니라 담당자가 확인한 문서 텍스트를 그대로 저장한다.
    @Column(nullable = false, columnDefinition = "text")
    private String value;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Importance importance;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 20)
    private VerificationStatus verificationStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "extraction_source", nullable = false, length = 30)
    private ExtractionSource extractionSource;

    @Column(name = "source_text_sha256", nullable = false, length = 64)
    private String sourceTextSha256;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    public static GroundTruthFact create(ProductDocument document, String label, String value,
                                         String sourceTextSha256, User creator) {
        GroundTruthFact fact = new GroundTruthFact();
        fact.documentId = document.getId();
        fact.replaceSnapshot(label, value, sourceTextSha256, creator);
        return fact;
    }

    // 문서의 확인 텍스트가 바뀌면 같은 행을 새 후보 스냅샷으로 교체한다.
    // 이전 텍스트에 내린 결정은 새 텍스트에 승계하지 않는다.
    public void replaceSnapshot(String label, String value, String sourceTextSha256, User creator) {
        this.label = label;
        this.value = value;
        this.importance = Importance.HIGH;
        this.verificationStatus = VerificationStatus.CANDIDATE;
        this.extractionSource = ExtractionSource.CONFIRMED_DOCUMENT;
        this.sourceTextSha256 = sourceTextSha256;
        this.createdBy = creator.getId();
        this.decidedBy = null;
        this.decidedAt = null;
    }

    public void decide(VerificationStatus status, User actor, OffsetDateTime decidedAt) {
        if (status == VerificationStatus.CANDIDATE) {
            throw new IllegalArgumentException("A fact decision must be VERIFIED or REJECTED");
        }
        this.verificationStatus = status;
        this.decidedBy = actor.getId();
        this.decidedAt = decidedAt;
    }

    public Long getVerifiedBy() {
        return decidedBy;
    }

    public OffsetDateTime getVerifiedAt() {
        return decidedAt;
    }
}
