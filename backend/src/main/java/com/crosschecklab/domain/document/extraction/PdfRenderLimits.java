package com.crosschecklab.domain.document.extraction;

import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

// PDFBox가 BufferedImage를 만들기 전에 동일한 치수 계산으로 메모리 사용량을 제한한다.
public final class PdfRenderLimits {

    public static final long MAX_PIXELS = 40_000_000L;

    private PdfRenderLimits() {
    }

    public static RenderSize validate(PDPage page, float dpi) {
        if (page == null || !Float.isFinite(dpi) || dpi <= 0) {
            throw new PdfRenderLimitException("PDF 페이지 렌더 DPI가 올바르지 않습니다.");
        }

        PDRectangle cropBox = page.getCropBox();
        if (cropBox == null) {
            throw new PdfRenderLimitException("PDF 페이지 crop box가 없습니다.");
        }
        float widthPoints = cropBox.getWidth();
        float heightPoints = cropBox.getHeight();
        if (!Float.isFinite(widthPoints)
                || !Float.isFinite(heightPoints)
                || widthPoints <= 0
                || heightPoints <= 0) {
            throw new PdfRenderLimitException("PDF 페이지 크기가 올바르지 않습니다.");
        }

        float scale = dpi / 72f;
        double scaledWidth = Math.max(Math.floor(widthPoints * scale), 1);
        double scaledHeight = Math.max(Math.floor(heightPoints * scale), 1);
        if (!Double.isFinite(scaledWidth)
                || !Double.isFinite(scaledHeight)
                || scaledWidth > Integer.MAX_VALUE
                || scaledHeight > Integer.MAX_VALUE) {
            throw new PdfRenderLimitException("PDF 페이지 렌더 크기가 올바르지 않습니다.");
        }

        int width = (int) scaledWidth;
        int height = (int) scaledHeight;
        long pixels = (long) width * height;
        if (pixels > MAX_PIXELS) {
            throw new PdfRenderLimitException(
                    "PDF 페이지가 최대 렌더 픽셀 수 " + MAX_PIXELS + "를 초과합니다.");
        }

        int rotation = page.getRotation();
        return rotation == 90 || rotation == 270
                ? new RenderSize(height, width, pixels)
                : new RenderSize(width, height, pixels);
    }

    public record RenderSize(int width, int height, long pixels) {
    }

    public static final class PdfRenderLimitException extends IllegalArgumentException {
        public PdfRenderLimitException(String message) {
            super(message);
        }
    }
}
