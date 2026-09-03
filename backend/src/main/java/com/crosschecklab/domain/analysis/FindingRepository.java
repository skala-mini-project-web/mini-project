package com.crosschecklab.domain.analysis;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingRepository extends JpaRepository<Finding, Long> {

    List<Finding> findByAnalysisIdOrderByIdAsc(Long analysisId);

    // 재시도 시 이전 회차 결과를 지운다. evidence_references / finding_affected_personas 는 FK ON DELETE CASCADE.
    void deleteByAnalysisId(Long analysisId);
}
