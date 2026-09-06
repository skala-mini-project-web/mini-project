package com.crosschecklab.domain.document;

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

@Entity
@Immutable
@Getter
@Table(name = "document_source_revisions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentSourceRevision {

    public static final int INITIAL_REVISION_NUMBER = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_document_id", nullable = false, updatable = false)
    private ProductDocument productDocument;

    @Column(name = "revision_number", nullable = false, updatable = false)
    private int revisionNumber;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(name = "media_type", nullable = false, updatable = false, length = 150)
    private String mediaType;

    @Column(name = "file_size", nullable = false, updatable = false)
    private long fileSize;

    @Column(nullable = false, updatable = false, length = 64)
    private String checksum;

    @Column(name = "storage_key", nullable = false, updatable = false, length = 500)
    private String storageKey;

    @Column(name = "source_hash", nullable = false, updatable = false, length = 64)
    private String sourceHash;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static DocumentSourceRevision initial(ProductDocument productDocument) {
        DocumentSourceRevision revision = new DocumentSourceRevision();
        revision.productDocument = productDocument;
        revision.revisionNumber = INITIAL_REVISION_NUMBER;
        revision.fileName = productDocument.getFileName();
        revision.mediaType = productDocument.getMediaType();
        revision.fileSize = productDocument.getFileSize();
        revision.checksum = productDocument.getChecksum();
        revision.storageKey = productDocument.getStorageKey();
        revision.sourceHash = productDocument.getChecksum();
        return revision;
    }

    public Long getProductDocumentId() {
        return productDocument.getId();
    }
}
