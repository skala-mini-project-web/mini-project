package com.crosschecklab.domain.groundtruth.dto;

import com.crosschecklab.domain.groundtruth.GroundTruthFact.VerificationStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record GroundTruthFactVerificationRequest(
        @NotNull VerificationStatus verificationStatus,
        @NotBlank String value
) {
}
