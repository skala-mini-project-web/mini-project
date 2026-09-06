package com.crosschecklab.domain.analysis;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface AnalysisRagRunRepository extends Repository<AnalysisRagRun, Long> {

    AnalysisRagRun save(AnalysisRagRun ragRun);

    @Query("""
            select ragRun
            from AnalysisRagRun ragRun
            where ragRun.analysisExecution.id = (
                select analysis.currentSuccessfulExecutionId
                from Analysis analysis
                where analysis.id = :analysisId
            )
            or (
                ragRun.analysis.id = :analysisId
                and (
                    select analysis.currentSuccessfulExecutionId
                    from Analysis analysis
                    where analysis.id = :analysisId
                ) is null
            )
            """)
    Optional<AnalysisRagRun> findByAnalysisId(@Param("analysisId") Long analysisId);

    Optional<AnalysisRagRun> findByAnalysisExecutionId(Long analysisExecutionId);

    @Query("""
            select ragRun
            from AnalysisRagRun ragRun
            where ragRun.analysisExecution.analysis.id = :analysisId
            order by ragRun.analysisExecution.attemptNo asc
            """)
    List<AnalysisRagRun> findAllByAnalysisExecutionAnalysisIdOrderByAnalysisExecutionAttemptNoAsc(
            @Param("analysisId") Long analysisId);
}
