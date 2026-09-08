package com.crosschecklab.analysis.provider.dto;

import com.crosschecklab.global.common.enums.PersonaCode;
import com.crosschecklab.global.common.enums.RedTeamRuleCode;
import com.crosschecklab.global.common.enums.Severity;
import java.util.List;

public record FindingPayload(
        String statement,
        Severity severity,
        RedTeamRuleCode policyRuleCode,
        List<PersonaCode> affectedPersonaCodes,
        List<Long> retrievedContextChunkIds,
        List<EvidenceSpanPayload> evidenceSpans,
        List<Long> knownFactIds,
        DocClaimPayload docClaim,
        String recommendation
) {

    public record EvidenceSpanPayload(Long chunkId, String excerpt) {
    }

    public record DocClaimPayload(String excerpt) {
    }
}
