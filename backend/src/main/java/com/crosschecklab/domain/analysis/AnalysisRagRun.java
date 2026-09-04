package com.crosschecklab.domain.analysis;

import com.crosschecklab.domain.evidence.EvidenceDocumentChunk;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * Immutable configuration and result trace for the single retrieval performed
 * for an analysis.
 */
@Entity
@Immutable
@Getter
@Table(name = "analysis_rag_runs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisRagRun {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analysis_id", nullable = false, updatable = false, unique = true)
    private Analysis analysis;

    @Column(name = "query_hash", nullable = false, updatable = false, columnDefinition = "char(64)")
    private String queryHash;

    @Column(name = "retrieval_version", nullable = false, updatable = false, length = 100)
    private String retrievalVersion;

    @Column(name = "embedding_model", nullable = false, updatable = false, length = 255)
    private String embeddingModel;

    @Column(name = "requested_result_count", nullable = false, updatable = false)
    private int requestedResultCount;

    @Column(name = "retrieved_at", nullable = false, updatable = false)
    private OffsetDateTime retrievedAt;

    @OneToMany(mappedBy = "ragRun", cascade = CascadeType.PERSIST)
    @OrderBy("rank ASC")
    private List<AnalysisRagRetrievalSnapshot> snapshots = new ArrayList<>();

    public static AnalysisRagRun create(
            Analysis analysis,
            String queryHash,
            String embeddingModel,
            String retrievalVersion,
            int requestedResultCount,
            OffsetDateTime retrievedAt
    ) {
        requireNonNull(analysis, "analysis");
        if (queryHash == null || !SHA_256.matcher(queryHash).matches()) {
            throw new IllegalArgumentException("queryHash must be a lowercase SHA-256 hash");
        }
        requireNonBlank(embeddingModel, "embeddingModel");
        requireNonBlank(retrievalVersion, "retrievalVersion");
        if (requestedResultCount <= 0) {
            throw new IllegalArgumentException("requestedResultCount must be positive");
        }
        requireNonNull(retrievedAt, "retrievedAt");

        AnalysisRagRun run = new AnalysisRagRun();
        run.analysis = analysis;
        run.queryHash = queryHash;
        run.embeddingModel = embeddingModel;
        run.retrievalVersion = retrievalVersion;
        run.requestedResultCount = requestedResultCount;
        run.retrievedAt = retrievedAt;
        return run;
    }

    public void addSnapshot(EvidenceDocumentChunk chunk, int rank, double similarity) {
        if (rank > requestedResultCount) {
            throw new IllegalArgumentException("rank must not exceed requestedResultCount");
        }
        if (snapshots.stream().anyMatch(snapshot -> snapshot.getRank() == rank)) {
            throw new IllegalArgumentException("rank must be unique within a retrieval run");
        }
        if (chunk != null && snapshots.stream().anyMatch(snapshot ->
                Objects.equals(snapshot.getEvidenceDocumentChunk().getId(), chunk.getId()))) {
            throw new IllegalArgumentException("chunk must be unique within a retrieval run");
        }
        snapshots.add(AnalysisRagRetrievalSnapshot.of(this, chunk, rank, similarity));
    }

    public List<AnalysisRagRetrievalSnapshot> getSnapshots() {
        return snapshots.stream()
                .sorted(Comparator.comparingInt(AnalysisRagRetrievalSnapshot::getRank))
                .toList();
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private static void requireNonNull(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
    }
}
