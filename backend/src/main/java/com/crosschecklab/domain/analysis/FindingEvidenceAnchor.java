package com.crosschecklab.domain.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Getter
@Table(
        name = "finding_evidence_anchors",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_finding_evidence_anchors_identity",
                columnNames = {
                        "finding_id", "source_role", "source_document_id", "source_revision_id",
                        "evidence_document_id", "retrieved_chunk_id", "source_hash", "page_number",
                        "utf8_start_offset", "utf8_end_offset", "excerpt_hash"
                })
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FindingEvidenceAnchor {

    public enum SourceRole {
        DOCUMENT_CLAIM,
        POLICY_REQUIREMENT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "finding_id", nullable = false, updatable = false)
    private Finding finding;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_role", nullable = false, updatable = false, length = 30)
    private SourceRole sourceRole;

    @Column(name = "source_document_id", updatable = false)
    private Long sourceDocumentId;

    @Column(name = "source_revision_id", updatable = false)
    private Long sourceRevisionId;

    @Column(name = "evidence_document_id", updatable = false)
    private Long evidenceDocumentId;

    @Column(name = "retrieved_chunk_id", updatable = false)
    private Long retrievedChunkId;

    @Column(name = "source_hash", nullable = false, updatable = false, length = 64)
    private String sourceHash;

    @Column(name = "page_number", nullable = false, updatable = false)
    private int pageNumber;

    @Column(name = "utf8_start_offset", nullable = false, updatable = false)
    private long utf8StartOffset;

    @Column(name = "utf8_end_offset", nullable = false, updatable = false)
    private long utf8EndOffset;

    @Column(name = "excerpt_hash", nullable = false, updatable = false, length = 64)
    private String excerptHash;

    @Column(name = "exact_excerpt", nullable = false, updatable = false, columnDefinition = "text")
    private String exactExcerpt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static FindingEvidenceAnchor documentClaim(
            Finding finding,
            long sourceDocumentId,
            long sourceRevisionId,
            String sourceHash,
            int pageNumber,
            long utf8StartOffset,
            long utf8EndOffset,
            String excerptHash,
            String exactExcerpt
    ) {
        requireExecutionBoundFinding(finding);
        requirePositive(sourceDocumentId, "sourceDocumentId");
        requirePositive(sourceRevisionId, "sourceRevisionId");
        validateExactEvidence(
                sourceHash, pageNumber, utf8StartOffset, utf8EndOffset, excerptHash, exactExcerpt);

        FindingEvidenceAnchor anchor = base(
                finding, SourceRole.DOCUMENT_CLAIM, sourceHash, pageNumber,
                utf8StartOffset, utf8EndOffset, excerptHash, exactExcerpt);
        anchor.sourceDocumentId = sourceDocumentId;
        anchor.sourceRevisionId = sourceRevisionId;
        return anchor;
    }

    public static FindingEvidenceAnchor policyRequirement(
            Finding finding,
            long evidenceDocumentId,
            long retrievedChunkId,
            String sourceHash,
            int pageNumber,
            long utf8StartOffset,
            long utf8EndOffset,
            String excerptHash,
            String exactExcerpt
    ) {
        requireExecutionBoundFinding(finding);
        requirePositive(evidenceDocumentId, "evidenceDocumentId");
        requirePositive(retrievedChunkId, "retrievedChunkId");
        validateExactEvidence(
                sourceHash, pageNumber, utf8StartOffset, utf8EndOffset, excerptHash, exactExcerpt);

        FindingEvidenceAnchor anchor = base(
                finding, SourceRole.POLICY_REQUIREMENT, sourceHash, pageNumber,
                utf8StartOffset, utf8EndOffset, excerptHash, exactExcerpt);
        anchor.evidenceDocumentId = evidenceDocumentId;
        anchor.retrievedChunkId = retrievedChunkId;
        return anchor;
    }

    private static FindingEvidenceAnchor base(
            Finding finding,
            SourceRole sourceRole,
            String sourceHash,
            int pageNumber,
            long utf8StartOffset,
            long utf8EndOffset,
            String excerptHash,
            String exactExcerpt
    ) {
        FindingEvidenceAnchor anchor = new FindingEvidenceAnchor();
        anchor.finding = finding;
        anchor.sourceRole = sourceRole;
        anchor.sourceHash = sourceHash;
        anchor.pageNumber = pageNumber;
        anchor.utf8StartOffset = utf8StartOffset;
        anchor.utf8EndOffset = utf8EndOffset;
        anchor.excerptHash = excerptHash;
        anchor.exactExcerpt = exactExcerpt;
        return anchor;
    }

    private static void requireExecutionBoundFinding(Finding finding) {
        if (finding == null || finding.getId() == null) {
            throw new IllegalArgumentException("finding must be persisted");
        }
        if (finding.getAnalysisExecution() == null
                || finding.getLineageId() == null
                || finding.getRevisionNumber() == null) {
            throw new IllegalArgumentException("finding must have an execution-bound revision identity");
        }
    }

    private static void validateExactEvidence(
            String sourceHash,
            int pageNumber,
            long utf8StartOffset,
            long utf8EndOffset,
            String excerptHash,
            String exactExcerpt
    ) {
        requireSha256(sourceHash, "sourceHash");
        requireSha256(excerptHash, "excerptHash");
        if (pageNumber <= 0) {
            throw new IllegalArgumentException("pageNumber must be positive");
        }
        if (utf8StartOffset < 0 || utf8EndOffset <= utf8StartOffset) {
            throw new IllegalArgumentException("UTF-8 offsets must define a non-empty forward range");
        }
        if (exactExcerpt == null || exactExcerpt.isEmpty()) {
            throw new IllegalArgumentException("exactExcerpt must not be empty");
        }
        long excerptByteLength = exactExcerpt.getBytes(StandardCharsets.UTF_8).length;
        if (utf8EndOffset - utf8StartOffset != excerptByteLength) {
            throw new IllegalArgumentException("UTF-8 range length must equal exactExcerpt byte length");
        }
        if (!sha256(exactExcerpt).equals(excerptHash)) {
            throw new IllegalArgumentException("excerptHash must be the SHA-256 of exactExcerpt UTF-8 bytes");
        }
    }

    private static void requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    private static void requireSha256(String value, String fieldName) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(fieldName + " must be lowercase SHA-256 hex");
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
