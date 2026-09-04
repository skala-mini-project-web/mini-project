package com.crosschecklab.analysis.rag;

public record RagRetrievedChunk(
        Long chunkId,
        Long evidenceDocumentId,
        String sourceHash,
        String chunkHash,
        int chunkOrdinal,
        String chunkingVersion,
        String embeddingModel,
        String chunkText,
        int rank,
        double similarity
) {
    public RagRetrievedChunk {
        if (chunkId == null || evidenceDocumentId == null) {
            throw new IllegalArgumentException("Retrieved chunk ids must not be null");
        }
        if (rank < 1 || chunkOrdinal < 0 || !Double.isFinite(similarity)) {
            throw new IllegalArgumentException("Retrieved chunk rank, index, or similarity is invalid");
        }
    }
}
