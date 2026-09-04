package com.crosschecklab.analysis.provider.dto;

import java.util.List;

public record AnalysisResult(
        int riskScore,
        String modelVersion,
        String promptVersion,
        List<FindingPayload> findings
) {
}
