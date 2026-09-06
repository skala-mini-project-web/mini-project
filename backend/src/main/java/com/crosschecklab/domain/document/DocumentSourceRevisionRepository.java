package com.crosschecklab.domain.document;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentSourceRevisionRepository extends JpaRepository<DocumentSourceRevision, Long> {

    List<DocumentSourceRevision> findAllByProductDocument_IdOrderByRevisionNumberAsc(Long productDocumentId);
}
