package com.crosschecklab.analysis.provider.dto;

import com.crosschecklab.global.common.enums.PersonaCode;
import com.crosschecklab.global.common.enums.Severity;
import java.util.List;

public record FindingPayload(
        String statement,
        Severity severity,
        List<PersonaCode> affectedPersonaCodes,
        List<Long> retrievedContextChunkIds,
        List<EvidenceSpanPayload> evidenceSpans,
        List<Long> knownFactIds,
        String recommendation
) {

    public FindingPayload {
        knownFactIds = knownFactIds == null ? List.of() : knownFactIds;
    }

    public FindingPayload(
            String statement,
            Severity severity,
            List<PersonaCode> affectedPersonaCodes,
            List<Long> retrievedContextChunkIds,
            List<Long> knownFactIds,
            String recommendation
    ) {
        this(statement, severity, affectedPersonaCodes, retrievedContextChunkIds, List.of(), knownFactIds,
                recommendation);
    }

    public FindingPayload(
            String statement,
            Severity severity,
            List<PersonaCode> affectedPersonaCodes,
            List<Long> retrievedContextChunkIds,
            String recommendation
    ) {
        this(statement, severity, affectedPersonaCodes, retrievedContextChunkIds, List.of(), List.of(),
                recommendation);
    }

    public record EvidenceSpanPayload(Long chunkId, String excerpt) {
    }
}
