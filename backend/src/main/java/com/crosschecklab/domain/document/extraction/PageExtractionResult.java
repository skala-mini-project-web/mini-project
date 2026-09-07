package com.crosschecklab.domain.document.extraction;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Selected text and immutable extraction provenance for one PDF page. */
public record PageExtractionResult(
        int pageNumber,
        PageExtractionMethod selectedMethod,
        String selectedText,
        String selectedTextHash,
        String pdfboxCandidateText,
        String pdfboxCandidateHash,
        String ocrRenderArtifactKey,
        String ocrRenderArtifactHash,
        Map<String, Object> ocrConfigSnapshot,
        OcrClient.EngineMetadata ocrEngine,
        BigDecimal ocrConfidence,
        List<String> ocrWarnings) {

    public PageExtractionResult {
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be positive");
        }
        Objects.requireNonNull(selectedMethod, "selectedMethod must not be null");
        Objects.requireNonNull(selectedText, "selectedText must not be null");
        Objects.requireNonNull(pdfboxCandidateText, "pdfboxCandidateText must not be null");
        requireMatchingHash(selectedText, selectedTextHash, "selectedTextHash");
        requireMatchingHash(pdfboxCandidateText, pdfboxCandidateHash, "pdfboxCandidateHash");

        if (selectedMethod == PageExtractionMethod.PDFBOX_TEXT) {
            if (!selectedText.equals(pdfboxCandidateText)) {
                throw new IllegalArgumentException("PDFBOX_TEXT must select the PDFBox candidate exactly");
            }
            if (ocrRenderArtifactKey != null || ocrRenderArtifactHash != null
                    || ocrConfigSnapshot != null || ocrEngine != null
                    || ocrConfidence != null || ocrWarnings != null) {
                throw new IllegalArgumentException("PDFBOX_TEXT must not carry OCR provenance");
            }
        } else {
            requireNonBlank(ocrRenderArtifactKey, "ocrRenderArtifactKey");
            requireSha256(ocrRenderArtifactHash, "ocrRenderArtifactHash");
            Objects.requireNonNull(ocrConfigSnapshot, "ocrConfigSnapshot must not be null");
            Objects.requireNonNull(ocrEngine, "ocrEngine must not be null");
            requireNonBlank(ocrEngine.name(), "ocrEngine.name");
            requireNonBlank(ocrEngine.version(), "ocrEngine.version");
            requireNonBlank(ocrEngine.tessdataVersion(), "ocrEngine.tessdataVersion");
            requireNonBlank(ocrEngine.language(), "ocrEngine.language");
            if (ocrConfidence == null
                    || ocrConfidence.compareTo(BigDecimal.ZERO) < 0
                    || ocrConfidence.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("ocrConfidence must be between 0 and 100");
            }
            Objects.requireNonNull(ocrWarnings, "ocrWarnings must not be null");
            ocrConfigSnapshot = Map.copyOf(ocrConfigSnapshot);
            ocrWarnings = List.copyOf(ocrWarnings);
        }
    }

    public static PageExtractionResult pdfBox(
            int pageNumber,
            String candidateText,
            String candidateHash) {
        return new PageExtractionResult(
                pageNumber,
                PageExtractionMethod.PDFBOX_TEXT,
                candidateText,
                candidateHash,
                candidateText,
                candidateHash,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static PageExtractionResult ocr(
            int pageNumber,
            String selectedText,
            String selectedTextHash,
            String candidateText,
            String candidateHash,
            String renderArtifactKey,
            String renderArtifactHash,
            Map<String, Object> ocrConfigSnapshot,
            OcrClient.EngineMetadata engine,
            BigDecimal confidence,
            List<String> warnings) {
        return new PageExtractionResult(
                pageNumber,
                PageExtractionMethod.OCR_KOR_ENG,
                selectedText,
                selectedTextHash,
                candidateText,
                candidateHash,
                renderArtifactKey,
                renderArtifactHash,
                ocrConfigSnapshot,
                engine,
                confidence,
                warnings);
    }

    private static void requireMatchingHash(String text, String hash, String fieldName) {
        requireSha256(hash, fieldName);
        if (!hash.equals(hashText(text))) {
            throw new IllegalArgumentException(fieldName + " must match its text");
        }
    }

    private static void requireSha256(String value, String fieldName) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(fieldName + " must be a lowercase SHA-256 digest");
        }
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    static String hashText(String text) {
        return hashBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    static String hashBytes(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
