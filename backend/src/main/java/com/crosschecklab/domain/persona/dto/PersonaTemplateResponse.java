package com.crosschecklab.domain.persona.dto;

import com.crosschecklab.domain.persona.PersonaTemplate;
import com.crosschecklab.global.common.enums.PersonaCode;
import java.util.List;
import java.util.Map;

public record PersonaTemplateResponse(
        Long personaTemplateId,
        PersonaCode code,
        String name,
        Map<String, Object> criteria,
        List<String> riskFocus,
        boolean active
) {

    // jsonb 컬럼이 NULL 이면 응답에서는 빈 값으로 내려 FE 가 null 체크를 하지 않게 한다.
    public static PersonaTemplateResponse from(PersonaTemplate template) {
        return new PersonaTemplateResponse(
                template.getId(),
                template.getCode(),
                template.getName(),
                template.getCriteria() == null ? Map.of() : template.getCriteria(),
                template.getRiskFocus() == null ? List.of() : template.getRiskFocus(),
                template.isActive()
        );
    }
}
