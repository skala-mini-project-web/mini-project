package com.crosschecklab.global.idempotency;

import com.crosschecklab.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(
        name = "idempotency_claims",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_idempotency_claims_actor_operation_key",
                columnNames = {"actor_id", "operation", "idempotency_key"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyClaim extends BaseTimeEntity {

    public enum IdempotencyOperation {
        ANALYSIS_CREATE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 32)
    private IdempotencyOperation operation;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
    private String fingerprint;

    @Column(name = "analysis_id")
    private Long analysisId;

    public boolean isCompleted() {
        return analysisId != null;
    }

    public void complete(Long analysisId) {
        this.analysisId = Objects.requireNonNull(analysisId, "analysisId");
    }
}
