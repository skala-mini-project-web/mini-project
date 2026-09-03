package com.crosschecklab.domain.redteam.dto;

import com.crosschecklab.domain.redteam.RedTeamRule;
import com.crosschecklab.global.common.enums.RedTeamRuleCode;

public record RedTeamRuleResponse(
        Long redTeamRuleId,
        RedTeamRuleCode code,
        String name,
        String description,
        Integer sortOrder,
        boolean active
) {

    public static RedTeamRuleResponse from(RedTeamRule rule) {
        return new RedTeamRuleResponse(
                rule.getId(),
                rule.getCode(),
                rule.getName(),
                rule.getDescription(),
                rule.getSortOrder(),
                rule.isActive()
        );
    }
}
