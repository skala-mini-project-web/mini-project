package com.crosschecklab.domain.evidence;

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
 * An immutable, embedded fragment of the exact evidence source version from
 * which it was indexed. Embeddings are written and searched through the RAG
 * persistence adapter; this entity maps the auditable source data.
 */
@Entity
@Immutable
@Getter
@Table(name = "evidence_document_chunks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EvidenceDocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evidence_document_id", nullable = false, updatable = false)
    private EvidenceDocument evidenceDocument;

    @Column(name = "source_hash", nullable = false, updatable = false, columnDefinition = "char(64)")
    private String sourceHash;

    @Column(name = "chunk_hash", nullable = false, updatable = false, columnDefinition = "char(64)")
    private String chunkHash;

    @Column(name = "chunk_ordinal", nullable = false, updatable = false)
    private int chunkOrdinal;

    @Column(name = "chunk_text", nullable = false, updatable = false, columnDefinition = "text")
    private String chunkText;

    @Column(name = "chunking_version", nullable = false, updatable = false, length = 100)
    private String chunkingVersion;

    @Column(name = "embedding_model", nullable = false, updatable = false, length = 255)
    private String embeddingModel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
