package com.crosschecklab.domain.groundtruth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroundTruthFactRepository extends JpaRepository<GroundTruthFact, Long> {

    Optional<GroundTruthFact> findByDocumentId(Long documentId);

    List<GroundTruthFact> findAllByDocumentIdOrderByIdAsc(Long documentId);

    List<GroundTruthFact> findAllByDocumentIdAndVerificationStatusOrderByIdAsc(
            Long documentId, GroundTruthFact.VerificationStatus verificationStatus);
}
