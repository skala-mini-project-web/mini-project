package com.crosschecklab.domain.analysis;

import com.crosschecklab.global.common.BaseTimeEntity;
import com.crosschecklab.global.common.enums.AnalysisStatus;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
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
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 분석 1건. 상태 전이(CREATED → RUNNING → COMPLETED/FAILED)를 이 엔티티가 직접 책임진다.
// 조인 테이블(analysis_personas / analysis_evidence_documents)은 순수 id 쌍이라
// 별도 엔티티 대신 @ElementCollection 으로 매핑한다.
@Entity
@Getter
@Table(name = "analyses")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Analysis extends BaseTimeEntity {

    // Provider 가 단일 호출로 결과를 돌려주므로 실제 중간 진행률이 없다. RUNNING 표시용 고정값.
    private static final int RUNNING_PROGRESS = 50;

    // 작업 스레드가 프로세스 중단 등으로 사라지면 CREATED/RUNNING 행이 남는다.
    // 이 시간 넘게 갱신이 없으면 죽은 작업으로 보고 재시도를 허용한다.
    private static final Duration STALE_AFTER = Duration.ofMinutes(5);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_document_id", nullable = false, updatable = false)
    private Long productDocumentId;

    @Column(name = "red_team_pack_id", nullable = false, updatable = false)
    private Long redTeamPackId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnalysisStatus status;

    @Column(nullable = false)
    private int progress;

    private Integer riskScore;

    @Column(length = 50)
    private String modelVersion;

    @Column(length = 50)
    private String promptVersion;

    @Column(nullable = false)
    private boolean requiresHumanApproval;

    @Column(nullable = false)
    private boolean retryable;

    @Column(length = 60)
    private String errorCode;

    private OffsetDateTime completedAt;

    // 동일 입력 재검증 차단용 지문. (product_document_id, input_hash) 부분 UNIQUE 인덱스와 짝을 이룬다.
    @Column(nullable = false, updatable = false, length = 64)
    private String inputHash;

    @ElementCollection
    @CollectionTable(name = "analysis_personas", joinColumns = @JoinColumn(name = "analysis_id"))
    @Column(name = "persona_template_id", nullable = false)
    private Set<Long> personaTemplateIds = new LinkedHashSet<>();

    @ElementCollection
    @CollectionTable(name = "analysis_evidence_documents", joinColumns = @JoinColumn(name = "analysis_id"))
    @Column(name = "evidence_document_id", nullable = false)
    private Set<Long> evidenceDocumentIds = new LinkedHashSet<>();

    public static Analysis create(Long productDocumentId, Long redTeamPackId,
                                  Set<Long> personaTemplateIds, Set<Long> evidenceDocumentIds, String inputHash) {
        Analysis analysis = new Analysis();
        analysis.productDocumentId = productDocumentId;
        analysis.redTeamPackId = redTeamPackId;
        analysis.personaTemplateIds = new LinkedHashSet<>(personaTemplateIds);
        analysis.evidenceDocumentIds = new LinkedHashSet<>(evidenceDocumentIds);
        analysis.inputHash = inputHash;
        analysis.status = AnalysisStatus.CREATED;
        analysis.progress = 0;
        analysis.requiresHumanApproval = true;
        analysis.retryable = false;
        return analysis;
    }

    public void markRunning() {
        this.status = AnalysisStatus.RUNNING;
        this.progress = RUNNING_PROGRESS;
        this.errorCode = null;
        this.retryable = false;
    }

    public void complete(int riskScore, String modelVersion, String promptVersion, OffsetDateTime completedAt) {
        this.status = AnalysisStatus.COMPLETED;
        this.progress = 100;
        this.riskScore = riskScore;
        this.modelVersion = modelVersion;
        this.promptVersion = promptVersion;
        this.completedAt = completedAt;
        this.errorCode = null;
        this.retryable = false;
    }

    // 202 로 수락된 뒤의 실패. 상태 조회는 200 으로 나가고 이 errorCode/retryable 을 함께 내려준다.
    public void fail(ErrorCode errorCode, boolean retryable) {
        this.status = AnalysisStatus.FAILED;
        this.errorCode = errorCode.name();
        this.retryable = retryable;
    }

    // ANA-003. 새 Analysis 를 만들지 않고 같은 행을 RUNNING 으로 되돌린다.
    public void requireRetryable(OffsetDateTime now) {
        if (status == AnalysisStatus.CREATED || status == AnalysisStatus.RUNNING) {
            if (getUpdatedAt().isAfter(now.minus(STALE_AFTER))) {
                throw new BusinessException(ErrorCode.ANALYSIS_ALREADY_RUNNING);
            }
            return; // 멈춘 지 오래된 작업은 되살린다
        }
        if (status != AnalysisStatus.FAILED || !retryable) {
            throw new BusinessException(ErrorCode.ANALYSIS_NOT_RETRYABLE);
        }
    }

    public void requireCompleted() {
        if (status != AnalysisStatus.COMPLETED && status != AnalysisStatus.IN_REVIEW) {
            throw new BusinessException(ErrorCode.ANALYSIS_NOT_COMPLETED);
        }
    }
}
