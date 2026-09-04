package com.crosschecklab.domain.analysis;

import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface AnalysisRagRunRepository extends Repository<AnalysisRagRun, Long> {

    AnalysisRagRun save(AnalysisRagRun ragRun);

    Optional<AnalysisRagRun> findByAnalysisId(Long analysisId);

    @Modifying
    @Query(value = "DELETE FROM analysis_rag_runs WHERE analysis_id = :analysisId", nativeQuery = true)
    int deleteByAnalysisId(@Param("analysisId") Long analysisId);
}
