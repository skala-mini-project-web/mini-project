package com.crosschecklab.analysis.provider.dto;

import com.crosschecklab.global.common.enums.EvidenceSourceType;
import com.crosschecklab.global.common.enums.PersonaCode;
import com.crosschecklab.global.common.enums.RedTeamRuleCode;
import java.util.List;

// Provider 입력. 근거 문서 원문 대신 선택 문서 id 와 검색된 청크만 전달한다.
// 필드명은 ai-service 계약(camelCase)과 1:1 이다.
public record AnalysisRequest(
        Long analysisId,
        String scenarioCode,
        String confirmedText,
        List<PersonaCode> personaCodes,
        String redTeamPackCode,
        List<RedTeamRuleCode> ruleCodes,
        List<Long> selectedEvidenceDocumentIds,
        List<RetrievedContextPayload> retrievedContexts,
        List<KnownFactPayload> knownFacts
) {

    public record RetrievedContextPayload(
            Long chunkId,
            Long evidenceDocumentId,
            EvidenceSourceType sourceType,
            String title,
            String chunkText,
            int rank,
            double similarity
    ) {
    }

    public record KnownFactPayload(Long factId, String text) {
    }
}
