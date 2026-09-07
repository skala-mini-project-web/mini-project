package com.crosschecklab.domain.document.extraction;

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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable provenance for the text candidate selected for one PDF page. */
@Entity
@Immutable
@Getter
@Table(
        name = "document_extraction_pages",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_document_extraction_pages_run_page",
                columnNames = {"extraction_run_id", "page_number"})
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentExtractionPage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "extraction_run_id", nullable = false, updatable = false)
    private DocumentExtractionRun extractionRun;

    @Column(name = "page_number", nullable = false, updatable = false)
    private int pageNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "selected_method", nullable = false, updatable = false, length = 30)
    private PageExtractionMethod selectedMethod;

    @Column(name = "selected_text", nullable = false, updatable = false, columnDefinition = "text")
    private String selectedText;

    @Column(name = "selected_text_hash", nullable = false, updatable = false, length = 64)
    private String selectedTextHash;

    @Column(name = "pdfbox_candidate_hash", nullable = false, updatable = false, length = 64)
    private String pdfboxCandidateHash;

    @Column(name = "ocr_render_artifact_key", updatable = false, length = 500)
    private String ocrRenderArtifactKey;

    @Column(name = "ocr_render_artifact_hash", updatable = false, length = 64)
    private String ocrRenderArtifactHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ocr_config_snapshot", updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> ocrConfigSnapshot;

    @Column(name = "ocr_engine", updatable = false, length = 100)
    private String ocrEngine;

    @Column(name = "ocr_model_version", updatable = false, length = 150)
    private String ocrModelVersion;

    @Column(name = "ocr_language", updatable = false, length = 50)
    private String ocrLanguage;

    @Column(name = "ocr_confidence", updatable = false, precision = 7, scale = 4)
    private BigDecimal ocrConfidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ocr_warnings", updatable = false, columnDefinition = "jsonb")
    private List<String> ocrWarnings;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static DocumentExtractionPage pdfBoxText(
            DocumentExtractionRun extractionRun,
            int pageNumber,
            String selectedText,
            String selectedTextHash,
            String pdfboxCandidateHash
    ) {
        DocumentExtractionPage page = base(
                extractionRun, pageNumber, selectedText, selectedTextHash, pdfboxCandidateHash);
        page.selectedMethod = PageExtractionMethod.PDFBOX_TEXT;
        return page;
    }

    public static DocumentExtractionPage ocrKorEng(
            DocumentExtractionRun extractionRun,
            int pageNumber,
            String selectedText,
            String selectedTextHash,
            String pdfboxCandidateHash,
            String renderArtifactKey,
            String renderArtifactHash,
            Map<String, Object> ocrConfigSnapshot,
            String ocrEngine,
            String ocrModelVersion,
            String ocrLanguage,
            BigDecimal ocrConfidence,
            List<String> ocrWarnings
    ) {
        DocumentExtractionPage page = base(
                extractionRun, pageNumber, selectedText, selectedTextHash, pdfboxCandidateHash);
        requireNonBlank(renderArtifactKey, "renderArtifactKey", 500);
        requireSha256(renderArtifactHash, "renderArtifactHash");
        if (ocrConfigSnapshot == null) {
            throw new IllegalArgumentException("ocrConfigSnapshot must not be null");
        }
        requireNonBlank(ocrEngine, "ocrEngine", 100);
        requireNonBlank(ocrModelVersion, "ocrModelVersion", 150);
        requireNonBlank(ocrLanguage, "ocrLanguage", 50);
        if (ocrConfidence == null
                || ocrConfidence.compareTo(BigDecimal.ZERO) < 0
                || ocrConfidence.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("ocrConfidence must be between 0 and 100");
        }
        if (ocrWarnings == null) {
            throw new IllegalArgumentException("ocrWarnings must not be null");
        }

        page.selectedMethod = PageExtractionMethod.OCR_KOR_ENG;
        page.ocrRenderArtifactKey = renderArtifactKey;
        page.ocrRenderArtifactHash = renderArtifactHash;
        page.ocrConfigSnapshot = Map.copyOf(ocrConfigSnapshot);
        page.ocrEngine = ocrEngine;
        page.ocrModelVersion = ocrModelVersion;
        page.ocrLanguage = ocrLanguage;
        page.ocrConfidence = ocrConfidence;
        page.ocrWarnings = List.copyOf(ocrWarnings);
        return page;
    }

    public Long getExtractionRunId() {
        return extractionRun.getId();
    }

    private static DocumentExtractionPage base(
            DocumentExtractionRun extractionRun,
            int pageNumber,
            String selectedText,
            String selectedTextHash,
            String pdfboxCandidateHash
    ) {
        if (extractionRun == null) {
            throw new IllegalArgumentException("extractionRun must not be null");
        }
        if (pageNumber <= 0) {
            throw new IllegalArgumentException("pageNumber must be positive");
        }
        if (selectedText == null) {
            throw new IllegalArgumentException("selectedText must not be null");
        }
        requireSha256(selectedTextHash, "selectedTextHash");
        if (!Objects.equals(selectedTextHash, hashText(selectedText))) {
            throw new IllegalArgumentException("selectedTextHash must match selectedText");
        }
        requireSha256(pdfboxCandidateHash, "pdfboxCandidateHash");

        DocumentExtractionPage page = new DocumentExtractionPage();
        page.extractionRun = extractionRun;
        page.pageNumber = pageNumber;
        page.selectedText = selectedText;
        page.selectedTextHash = selectedTextHash;
        page.pdfboxCandidateHash = pdfboxCandidateHash;
        return page;
    }

    private static void requireSha256(String value, String fieldName) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(fieldName + " must be a lowercase SHA-256 hex digest");
        }
    }

    private static void requireNonBlank(String value, String fieldName, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " must not exceed " + maximumLength + " characters");
        }
    }

    private static String hashText(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
