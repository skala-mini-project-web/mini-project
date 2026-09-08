package com.crosschecklab.domain.document.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PDF 페이지 렌더 제한")
class PdfRenderLimitsTest {

    @Test
    @DisplayName("A4 페이지는 300 DPI OCR 렌더 제한 안에 있다")
    void acceptsOrdinaryA4PageAt300Dpi() {
        PdfRenderLimits.RenderSize size =
                PdfRenderLimits.validate(new PDPage(PDRectangle.A4), 300);

        assertThat(size.width()).isBetween(2_479, 2_481);
        assertThat(size.height()).isBetween(3_507, 3_509);
        assertThat(size.pixels()).isLessThan(PdfRenderLimits.MAX_PIXELS);
    }

    @Test
    @DisplayName("PDFBox와 같은 crop box 및 회전 기준으로 픽셀 수를 계산한다")
    void usesCropBoxAndRotationLikePdfBox() {
        PDPage page = new PDPage(new PDRectangle(1_000, 1_000));
        page.setCropBox(new PDRectangle(400, 200));
        page.setRotation(90);
        page.setUserUnit(10);

        PdfRenderLimits.RenderSize size = PdfRenderLimits.validate(page, 72);

        assertThat(size).isEqualTo(new PdfRenderLimits.RenderSize(200, 400, 80_000));
    }

    @Test
    @DisplayName("최대 픽셀 수 경계는 허용하고 초과 페이지는 거부한다")
    void enforcesPixelBoundaryWithoutRendering() {
        assertThat(PdfRenderLimits.validate(
                new PDPage(new PDRectangle(8_000, 5_000)), 72).pixels())
                .isEqualTo(PdfRenderLimits.MAX_PIXELS);

        assertThatThrownBy(() -> PdfRenderLimits.validate(
                new PDPage(new PDRectangle(8_001, 5_000)), 72))
                .isInstanceOf(PdfRenderLimits.PdfRenderLimitException.class)
                .hasMessageContaining("최대 렌더 픽셀 수");
    }

    @Test
    @DisplayName("잘못된 페이지 치수와 DPI는 렌더 전에 거부한다")
    void rejectsInvalidDimensionsAndDpi() {
        PDPage nonFinitePage = new PDPage() {
            @Override
            public PDRectangle getCropBox() {
                return new PDRectangle(100, 100) {
                    @Override
                    public float getWidth() {
                        return Float.NaN;
                    }
                };
            }
        };

        assertThatThrownBy(() -> PdfRenderLimits.validate(
                new PDPage(new PDRectangle(0, 100)), 300))
                .isInstanceOf(PdfRenderLimits.PdfRenderLimitException.class)
                .hasMessageContaining("페이지 크기");
        assertThatThrownBy(() -> PdfRenderLimits.validate(nonFinitePage, 300))
                .isInstanceOf(PdfRenderLimits.PdfRenderLimitException.class)
                .hasMessageContaining("페이지 크기");
        assertThatThrownBy(() -> PdfRenderLimits.validate(
                new PDPage(PDRectangle.A4), Float.NaN))
                .isInstanceOf(PdfRenderLimits.PdfRenderLimitException.class)
                .hasMessageContaining("DPI");
        assertThatThrownBy(() -> PdfRenderLimits.validate(
                new PDPage(PDRectangle.A4), 0))
                .isInstanceOf(PdfRenderLimits.PdfRenderLimitException.class)
                .hasMessageContaining("DPI");
    }

    @Test
    @DisplayName("양수인 sub-pixel crop box는 PDFBox처럼 최소 1픽셀로 계산한다")
    void preservesPdfBoxMinimumPixelEdge() {
        PdfRenderLimits.RenderSize size = PdfRenderLimits.validate(
                new PDPage(new PDRectangle(0.01f, 0.01f)), 72);

        assertThat(size).isEqualTo(new PdfRenderLimits.RenderSize(1, 1, 1));
    }
}
