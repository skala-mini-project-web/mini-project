package com.crosschecklab.domain.analysis;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisRagRetrievalSnapshotRepository
        extends JpaRepository<AnalysisRagRetrievalSnapshot, Long> {

    List<AnalysisRagRetrievalSnapshot> findAllByRagRunIdOrderByRankAsc(Long ragRunId);
}
