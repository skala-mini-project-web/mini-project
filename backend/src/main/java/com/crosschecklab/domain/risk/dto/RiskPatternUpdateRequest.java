package com.crosschecklab.domain.risk.dto;

import com.crosschecklab.global.common.enums.RiskPatternStatus;
import jakarta.validation.constraints.Size;

/**
 * RISK-002 요청. 이름 다듬기와 활성화를 한 번에 처리한다.
 *
 * <p>두 필드 모두 선택이다. 이름만 고칠 수도, 이름을 그대로 두고 활성화만 할 수도 있다.
 * PUT 이 아니라 PATCH 인 이유는 severity·findingId 처럼 승격 시점에 확정되어
 * 바뀌면 안 되는 값이 함께 있기 때문이다.
 *
 * @param name   비우면 기존 이름을 유지한다. 공백만 보내면 400 이다
 * @param status {@code ACTIVE} 만 의미가 있다. DRAFT 로 되돌리면 409 다
 */
public record RiskPatternUpdateRequest(
        @Size(min = 1, max = 255, message = "패턴 이름은 1~255자여야 합니다.")
        String name,

        RiskPatternStatus status
) {
}
