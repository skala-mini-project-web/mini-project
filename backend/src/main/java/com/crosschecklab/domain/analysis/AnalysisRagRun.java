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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
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
 * Immutable configuration and result trace for one execution's retrieval.
 * Rows without an execution are legacy records and retain only their historical
 * analysis association.
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "analysis_id", updatable = false)
    private Analysis analysis;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "analysis_execution_id", updatable = false)
    private AnalysisExecution analysisExecution;

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
            AnalysisExecution analysisExecution,
            String queryHash,
            String embeddingModel,
            String retrievalVersion,
            int requestedResultCount,
            OffsetDateTime retrievedAt
    ) {
        requireNonNull(analysisExecution, "analysisExecution");
        if (analysisExecution.getStatus() != AnalysisExecution.Status.RUNNING) {
            throw new IllegalArgumentException("analysisExecution must be running");
        }
        if (queryHash == null || !SHA_256.matcher(queryHash).matches()) {
            throw new IllegalArgumentException("queryHash must be a lowercase SHA-256 hash");
        }
        requireNonBlank(embeddingModel, "embeddingModel", 255);
        requireNonBlank(retrievalVersion, "retrievalVersion", 100);
        if (!retrievalVersion.equals(analysisExecution.getRetrievalVersion())) {
            throw new IllegalArgumentException("retrievalVersion must match analysisExecution");
        }
        if (requestedResultCount <= 0) {
            throw new IllegalArgumentException("requestedResultCount must be positive");
        }
        requireNonNull(retrievedAt, "retrievedAt");
        if (retrievedAt.isBefore(analysisExecution.getStartedAt())) {
            throw new IllegalArgumentException("retrievedAt must not be before execution startedAt");
        }

        AnalysisRagRun run = new AnalysisRagRun();
        run.analysisExecution = analysisExecution;
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

    private static void requireNonBlank(String value, String fieldName, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " must not exceed " + maximumLength + " characters");
        }
    }

    private static void requireNonNull(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
    }
}
