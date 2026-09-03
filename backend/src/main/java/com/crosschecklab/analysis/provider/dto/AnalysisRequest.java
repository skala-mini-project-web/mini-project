package com.crosschecklab.analysis.provider.dto;

import com.crosschecklab.global.common.enums.EvidenceSourceType;
import com.crosschecklab.global.common.enums.PersonaCode;
import com.crosschecklab.global.common.enums.RedTeamRuleCode;
import java.util.List;

// Provider 입력. DB id 가 아니라 분석에 필요한 내용(확정 텍스트·근거 원문·코드)을 모두 담아 전달한다.
// 필드명은 ai-service 계약(camelCase)과 1:1 이다.
public record AnalysisRequest(
        Long analysisId,
        String scenarioCode,
        String confirmedText,
        List<PersonaCode> personaCodes,
        String redTeamPackCode,
        List<RedTeamRuleCode> ruleCodes,
        List<EvidenceDocumentPayload> evidenceDocuments
) {

    public record EvidenceDocumentPayload(
            Long id,
            EvidenceSourceType sourceType,
            String title,
            String content
    ) {
    }
}
