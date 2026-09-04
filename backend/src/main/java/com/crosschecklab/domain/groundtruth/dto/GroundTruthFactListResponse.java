package com.crosschecklab.domain.groundtruth.dto;

import com.crosschecklab.domain.groundtruth.GroundTruthFact;
import java.util.List;

public record GroundTruthFactListResponse(
        List<GroundTruthFactResponse> items
) {

    public static GroundTruthFactListResponse from(List<GroundTruthFact> facts) {
        return new GroundTruthFactListResponse(
                facts.stream().map(GroundTruthFactResponse::from).toList()
        );
    }
}
