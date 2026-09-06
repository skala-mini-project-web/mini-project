package com.crosschecklab.domain.analysis;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingEvidenceAnchorRepository extends JpaRepository<FindingEvidenceAnchor, Long> {

    List<FindingEvidenceAnchor> findAllByFindingIdOrderByIdAsc(Long findingId);

    List<FindingEvidenceAnchor> findAllByFindingAnalysisExecutionIdOrderByFindingIdAscIdAsc(
            Long analysisExecutionId);
}
