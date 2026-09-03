package com.crosschecklab.domain.redteam.dto;

import com.crosschecklab.domain.redteam.RedTeamPack;
import com.crosschecklab.global.common.enums.RedTeamRuleCode;
import java.util.List;

// ruleCodes 는 rules 에서 뽑은 code 배열이다.
// 규칙 상세 없이 코드 목록만 필요한 화면(분석 요청 시 Pack 선택)이 있어 함께 내린다.
public record RedTeamPackResponse(
        Long redTeamPackId,
        String code,
        String name,
        String description,
        boolean active,
        List<RedTeamRuleCode> ruleCodes,
        List<RedTeamRuleResponse> rules
) {

    public static RedTeamPackResponse from(RedTeamPack pack) {
        List<RedTeamRuleResponse> rules = pack.getRules().stream()
                .map(RedTeamRuleResponse::from)
                .toList();
        return new RedTeamPackResponse(
                pack.getId(),
                pack.getCode(),
                pack.getName(),
                pack.getDescription(),
                pack.isActive(),
                rules.stream().map(RedTeamRuleResponse::code).toList(),
                rules
        );
    }
}
