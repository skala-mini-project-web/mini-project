package com.crosschecklab.domain.analysis;

import com.crosschecklab.domain.groundtruth.GroundTruthFact;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * Immutable copy of a verified fact used when an analysis was accepted.
 * The source association preserves provenance; label and value deliberately do
 * not delegate to the mutable current fact.
 */
@Entity
@Immutable
@Getter
@Table(name = "analysis_ground_truth_fact_snapshots")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisGroundTruthFactSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analysis_id", nullable = false, updatable = false)
    private Analysis analysis;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ground_truth_fact_id", nullable = false, updatable = false)
    private GroundTruthFact groundTruthFact;

    @Column(nullable = false, updatable = false)
    private String label;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String value;

    public static AnalysisGroundTruthFactSnapshot of(Analysis analysis, GroundTruthFact groundTruthFact) {
        AnalysisGroundTruthFactSnapshot snapshot = new AnalysisGroundTruthFactSnapshot();
        snapshot.analysis = analysis;
        snapshot.groundTruthFact = groundTruthFact;
        snapshot.label = groundTruthFact.getLabel();
        snapshot.value = groundTruthFact.getValue();
        return snapshot;
    }
}
