package com.crosschecklab.domain.document;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentSourceRevisionRepository extends JpaRepository<DocumentSourceRevision, Long> {

    List<DocumentSourceRevision> findAllByProductDocument_IdOrderByRevisionNumberAsc(Long productDocumentId);

    Optional<DocumentSourceRevision> findByProductDocument_IdAndSourceHash(
            Long productDocumentId, String sourceHash);
}
