package com.crosschecklab.domain.groundtruth.dto;

import com.crosschecklab.domain.groundtruth.GroundTruthFact;
import com.crosschecklab.domain.groundtruth.GroundTruthFact.ExtractionSource;
import com.crosschecklab.domain.groundtruth.GroundTruthFact.Importance;
import com.crosschecklab.domain.groundtruth.GroundTruthFact.VerificationStatus;
import java.time.OffsetDateTime;

public record GroundTruthFactResponse(
        Long factId,
        String label,
        String value,
        Importance importance,
        VerificationStatus verificationStatus,
        ExtractionSource extractionSource,
        Long verifiedBy,
        OffsetDateTime verifiedAt
) {

    public static GroundTruthFactResponse from(GroundTruthFact fact) {
        return new GroundTruthFactResponse(
                fact.getId(),
                fact.getLabel(),
                fact.getValue(),
                fact.getImportance(),
                fact.getVerificationStatus(),
                fact.getExtractionSource(),
                fact.getVerifiedBy(),
                fact.getVerifiedAt()
        );
    }
}
