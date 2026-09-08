package com.crosschecklab.analysis.provider.dto;

import java.util.List;

public record AnalysisResult(
        Integer riskScore,
        String modelVersion,
        String promptVersion,
        List<FindingPayload> findings
) {
    public static final int MAX_FINDINGS = 20;
}
