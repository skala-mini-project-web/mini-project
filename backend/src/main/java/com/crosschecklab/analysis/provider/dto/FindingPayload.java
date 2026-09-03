package com.crosschecklab.analysis.provider.dto;

import com.crosschecklab.global.common.enums.PersonaCode;
import com.crosschecklab.global.common.enums.Severity;
import java.util.List;

public record FindingPayload(
        String statement,
        Severity severity,
        List<PersonaCode> affectedPersonaCodes,
        List<EvidenceRefPayload> evidenceReferences,
        String recommendation
) {

    public record EvidenceRefPayload(Long evidenceDocumentId, String excerpt) {
    }
}
