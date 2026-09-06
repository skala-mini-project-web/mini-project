package com.crosschecklab.domain.analysis;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface AnalysisExecutionRepository extends Repository<AnalysisExecution, Long> {

    AnalysisExecution save(AnalysisExecution execution);

    Optional<AnalysisExecution> findById(Long id);

    Optional<AnalysisExecution> findByExecutionToken(String executionToken);

    Optional<AnalysisExecution> findByAnalysisIdAndExecutionToken(Long analysisId, String executionToken);

    Optional<AnalysisExecution> findByAnalysisIdAndAttemptNo(Long analysisId, int attemptNo);

    Optional<AnalysisExecution> findTopByAnalysisIdOrderByAttemptNoDesc(Long analysisId);

    List<AnalysisExecution> findAllByAnalysisIdOrderByAttemptNoAsc(Long analysisId);
}
