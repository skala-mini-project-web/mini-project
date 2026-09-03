package com.crosschecklab.domain.document.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * DOC-003 추출 텍스트 수정·확인 요청.
 *
 * <p>부분 수정이 아니라 화면에 보이는 전체 텍스트를 그대로 보낸다.
 * 확인(confirmed)을 분리된 엔드포인트로 두지 않은 이유는, 담당자가 텍스트를 고치고 확인하는 동작이
 * 한 번의 저장으로 끝나야 중간에 "고쳤지만 확인 안 된" 상태가 생기지 않기 때문이다.
 *
 * @param confirmed true 면 확인자·확인 시각을 기록하고, false 면 확인을 해제한다
 */
public record DocumentTextUpdateRequest(

        @Schema(description = "수정된 추출 텍스트 전체", example = "튼튼정기예금 상품설명서\n...")
        @NotBlank(message = "추출 텍스트는 비어 있을 수 없습니다.")
        @Size(max = 200_000, message = "추출 텍스트는 200,000자를 넘을 수 없습니다.")
        String extractedText,

        @Schema(description = "확인 완료 여부", example = "true")
        @NotNull(message = "confirmed 는 필수입니다.")
        Boolean confirmed
) {
}
