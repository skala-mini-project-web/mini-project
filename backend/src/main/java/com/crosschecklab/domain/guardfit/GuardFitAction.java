package com.crosschecklab.domain.guardfit;

import com.crosschecklab.global.common.BaseTimeEntity;
import com.crosschecklab.global.common.enums.GuardFitActionType;
import com.crosschecklab.global.common.enums.GuardFitStatus;
import com.crosschecklab.global.error.BusinessException;
import com.crosschecklab.global.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// ACTIVE 위험 패턴에 붙이는 보호조치 후보 (GF-001~003).
// 상태는 DRAFT → APPROVED 단방향이며 DISCARDED 는 없다(API 명세 §1.2 확정 사항).
// 승인된 뒤에는 문구가 바뀌면 안 되므로 APPROVED 는 종착 상태다.
// risk_pattern_id 로 패턴 → Finding → Review 까지 그대로 역추적된다.
@Entity
@Getter
@Table(name = "guardfit_actions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GuardFitAction extends BaseTimeEntity {

    // label · placement 컬럼이 VARCHAR(255) 다. 요청 DTO 에서 @Size 로 먼저 걸러 400 으로 끊는다.
    private static final int TEXT_MAX_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 어느 패턴에 대한 보호조치인지. 다른 패턴으로 옮겨 붙이는 건 새 후보를 만드는 것과 같으므로 갱신하지 않는다.
    @Column(name = "risk_pattern_id", nullable = false, updatable = false)
    private Long riskPatternId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 20)
    private GuardFitActionType actionType;

    @Column(nullable = false, length = TEXT_MAX_LENGTH)
    private String label;

    @Column(nullable = false, length = TEXT_MAX_LENGTH)
    private String placement;

    @Column(nullable = false)
    private boolean required;

    // 화면에 실제로 노출될 문구. 길이 제한이 없어 TEXT 다.
    @Column(columnDefinition = "text")
    private String preview;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GuardFitStatus status;

    // 마지막으로 손댄 검토자. 생성 시점에는 만든 사람이 그대로 들어간다.
    // (created_by 컬럼은 확정 ERD 에 없어 별도로 두지 않는다)
    @Column(name = "updated_by")
    private Long updatedBy;

    // GF-001. 후보는 항상 DRAFT 로 태어난다. 승인은 GF-003 의 몫이다.
    public static GuardFitAction draft(Long riskPatternId, GuardFitActionType actionType, String label,
                                       String placement, boolean required, String preview, Long authorId) {
        GuardFitAction action = new GuardFitAction();
        action.riskPatternId = riskPatternId;
        action.actionType = actionType;
        action.label = label;
        action.placement = placement;
        action.required = required;
        action.preview = preview;
        action.status = GuardFitStatus.DRAFT;
        action.updatedBy = authorId;
        return action;
    }

    // GF-003. 편집과 승인이 한 요청이라 상태 검사를 먼저 한다.
    // 승인본은 상품 담당자가 이미 배포 가이드로 보고 있으므로 이후 변경은 409 로 끊는다.
    public void edit(GuardFitActionType actionType, String label, String placement, boolean required,
                     String preview, GuardFitStatus decision, Long editorId) {
        if (status == GuardFitStatus.APPROVED) {
            throw new BusinessException(ErrorCode.ACTION_ALREADY_FINALIZED);
        }
        this.actionType = actionType;
        this.label = label;
        this.placement = placement;
        this.required = required;
        this.preview = preview;
        this.status = decision;
        this.updatedBy = editorId;
    }
}
