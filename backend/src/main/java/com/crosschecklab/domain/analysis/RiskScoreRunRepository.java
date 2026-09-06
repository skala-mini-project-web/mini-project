package com.crosschecklab.domain.analysis;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface RiskScoreRunRepository extends Repository<RiskScoreRun, Long> {

    RiskScoreRun save(RiskScoreRun riskScoreRun);

    Optional<RiskScoreRun> findById(Long id);

    Optional<RiskScoreRun> findByAnalysisExecutionIdAndPolicyVersionAndInputFingerprint(
            Long analysisExecutionId,
            String policyVersion,
            String inputFingerprint);

    boolean existsByAnalysisExecutionIdAndPolicyVersionAndInputFingerprint(
            Long analysisExecutionId,
            String policyVersion,
            String inputFingerprint);

    List<RiskScoreRun> findAllByAnalysisExecutionIdOrderByCreatedAtDescIdDesc(
            Long analysisExecutionId);
}
