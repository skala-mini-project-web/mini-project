package com.crosschecklab.domain.analysis;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisGroundTruthFactSnapshotRepository
        extends JpaRepository<AnalysisGroundTruthFactSnapshot, Long> {

    List<AnalysisGroundTruthFactSnapshot> findAllByAnalysisIdOrderByIdAsc(Long analysisId);
}
