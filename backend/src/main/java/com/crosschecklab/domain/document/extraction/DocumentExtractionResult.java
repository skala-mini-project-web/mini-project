package com.crosschecklab.domain.document.extraction;

import java.util.List;
import java.util.Objects;

/** Extracted document text together with ordered per-page provenance when available. */
public record DocumentExtractionResult(
        String text,
        String textHash,
        String sourceHash,
        List<PageExtractionResult> pages) {

    public DocumentExtractionResult {
        Objects.requireNonNull(text, "text must not be null");
        requireSha256(textHash, "textHash");
        requireSha256(sourceHash, "sourceHash");
        if (!textHash.equals(PageExtractionResult.hashText(text))) {
            throw new IllegalArgumentException("textHash must match text");
        }
        pages = List.copyOf(Objects.requireNonNull(pages, "pages must not be null"));
        if (!pages.isEmpty()) {
            StringBuilder assembled = new StringBuilder();
            for (int index = 0; index < pages.size(); index++) {
                PageExtractionResult page = pages.get(index);
                if (page.pageNumber() != index + 1) {
                    throw new IllegalArgumentException("pages must be complete and ordered from page 1");
                }
                assembled.append(page.selectedText());
            }
            if (!text.contentEquals(assembled)) {
                throw new IllegalArgumentException("text must be the ordered selected page text");
            }
        }
    }

    public static DocumentExtractionResult ofPages(
            String sourceHash,
            List<PageExtractionResult> pages) {
        Objects.requireNonNull(pages, "pages must not be null");
        if (pages.isEmpty()) {
            throw new IllegalArgumentException(
                    "structured page extraction must contain at least one page");
        }
        String text = pages.stream()
                .map(PageExtractionResult::selectedText)
                .collect(java.util.stream.Collectors.joining());
        return new DocumentExtractionResult(
                text, PageExtractionResult.hashText(text), sourceHash, pages);
    }

    public static DocumentExtractionResult withoutPageProvenance(String text, String sourceHash) {
        return new DocumentExtractionResult(
                text, PageExtractionResult.hashText(text), sourceHash, List.of());
    }

    public boolean hasPageResults() {
        return !pages.isEmpty();
    }

    private static void requireSha256(String value, String fieldName) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(fieldName + " must be a lowercase SHA-256 digest");
        }
    }
}
