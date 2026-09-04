package com.crosschecklab.domain.evidence;

import java.util.List;
import org.springframework.data.repository.Repository;

public interface EvidenceDocumentChunkRepository extends Repository<EvidenceDocumentChunk, Long> {

    List<EvidenceDocumentChunk> findAllByEvidenceDocumentIdAndSourceHashAndChunkingVersionAndEmbeddingModelOrderByChunkOrdinalAsc(
            Long evidenceDocumentId,
            String sourceHash,
            String chunkingVersion,
            String embeddingModel
    );
}
