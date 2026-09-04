package com.crosschecklab.domain.analysis;

import com.crosschecklab.domain.evidence.EvidenceDocument;
import com.crosschecklab.domain.evidence.EvidenceDocumentChunk;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * Immutable retrieval evidence recorded at analysis time. Snapshot values are
 * copied from the indexed chunk so later provenance queries never depend on
 * reconstructing the text or its hashes.
 */
@Entity
@Immutable
@Getter
@Table(name = "analysis_rag_retrieval_snapshots")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisRagRetrievalSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rag_run_id", nullable = false, updatable = false)
    private AnalysisRagRun ragRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evidence_document_chunk_id", nullable = false, updatable = false)
    private EvidenceDocumentChunk evidenceDocumentChunk;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evidence_document_id", nullable = false, updatable = false)
    private EvidenceDocument evidenceDocument;

    @Column(nullable = false, updatable = false)
    private int rank;

    @Column(nullable = false, updatable = false)
    private double similarity;

    @Column(name = "source_hash", nullable = false, updatable = false, columnDefinition = "char(64)")
    private String sourceHash;

    @Column(name = "chunk_hash", nullable = false, updatable = false, columnDefinition = "char(64)")
    private String chunkHash;

    @Column(name = "chunk_text", nullable = false, updatable = false, columnDefinition = "text")
    private String chunkText;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static AnalysisRagRetrievalSnapshot of(
            AnalysisRagRun ragRun,
            EvidenceDocumentChunk chunk,
            int rank,
            double similarity
    ) {
        if (ragRun == null) {
            throw new IllegalArgumentException("ragRun must not be null");
        }
        if (chunk == null) {
            throw new IllegalArgumentException("chunk must not be null");
        }
        if (rank <= 0) {
            throw new IllegalArgumentException("rank must be positive");
        }
        if (!Double.isFinite(similarity) || similarity < -1.0 || similarity > 1.0) {
            throw new IllegalArgumentException("similarity must be finite and between -1 and 1");
        }

        AnalysisRagRetrievalSnapshot snapshot = new AnalysisRagRetrievalSnapshot();
        snapshot.ragRun = ragRun;
        snapshot.evidenceDocumentChunk = chunk;
        snapshot.evidenceDocument = chunk.getEvidenceDocument();
        snapshot.rank = rank;
        snapshot.similarity = similarity;
        snapshot.sourceHash = chunk.getSourceHash();
        snapshot.chunkHash = chunk.getChunkHash();
        snapshot.chunkText = chunk.getChunkText();
        return snapshot;
    }
}
