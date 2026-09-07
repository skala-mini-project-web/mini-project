package com.crosschecklab.domain.document.extraction;

import com.crosschecklab.domain.document.DocumentMediaType;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// PDF 본문 추출. real-extraction 프로파일에서만 활성화된다.
@Component
@Profile("real-extraction")
public class PdfBoxTextExtractor implements BinaryTextExtractor {

    private static final int OCR_RENDER_DPI = 300;
    private static final int MINIMUM_TEXT_CODEPOINTS = 32;
    private static final double MINIMUM_READABLE_RATIO = 0.70;
    private static final String PNG_MEDIA_TYPE = "image/png";

    private final OcrClient ocrClient;

    public PdfBoxTextExtractor() {
        this((OcrClient) null);
    }

    public PdfBoxTextExtractor(OcrClient ocrClient) {
        this.ocrClient = ocrClient;
    }

    @Autowired
    public PdfBoxTextExtractor(ObjectProvider<OcrClient> ocrClientProvider) {
        this(ocrClientProvider.getIfAvailable());
    }

    @Override
    public DocumentMediaType supportedMediaType() {
        return DocumentMediaType.PDF;
    }

    @Override
    public String extract(InputStream content) {
        try {
            return extractResult(content.readAllBytes()).text();
        } catch (IOException e) {
            throw new TextExtractionException("PDF 텍스트 추출에 실패했습니다.", e);
        }
    }

    public DocumentExtractionResult extractResult(byte[] content) {
        String sourceHash = hash(content);
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.getNumberOfPages() == 0) {
                throw new TextExtractionException(
                        "PDF에 provenance를 생성할 페이지가 없습니다.");
            }
            PDFTextStripper textStripper = new PDFTextStripper();
            PDFRenderer renderer = new PDFRenderer(document);
            List<PageExtractionResult> pages = new ArrayList<>(document.getNumberOfPages());
            for (int pageNumber = 1; pageNumber <= document.getNumberOfPages(); pageNumber++) {
                textStripper.setStartPage(pageNumber);
                textStripper.setEndPage(pageNumber);
                String candidateText = textStripper.getText(document);
                String candidateHash = PageExtractionResult.hashText(candidateText);
                if (isTextAdequate(candidateText)) {
                    pages.add(PageExtractionResult.pdfBox(pageNumber, candidateText, candidateHash));
                } else {
                    pages.add(extractWithOcr(
                            renderer, pageNumber, sourceHash, candidateText, candidateHash));
                }
            }
            return DocumentExtractionResult.ofPages(sourceHash, pages);
        } catch (OcrClient.OcrException exception) {
            throw new TextExtractionException(
                    "PDF 페이지 OCR에 실패했습니다: " + exception.getMessage(),
                    exception.retryable());
        } catch (TextExtractionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new TextExtractionException("PDF 텍스트 추출에 실패했습니다.", exception);
        } catch (RuntimeException exception) {
            throw new TextExtractionException("PDF 페이지 OCR 결과 처리에 실패했습니다.", exception);
        }
    }

    private PageExtractionResult extractWithOcr(
            PDFRenderer renderer,
            int pageNumber,
            String sourceHash,
            String candidateText,
            String candidateHash) throws IOException {
        if (ocrClient == null) {
            throw new TextExtractionException(
                    "PDF 페이지 " + pageNumber + "에 OCR이 필요하지만 OCR client가 등록되어 있지 않습니다.");
        }
        BufferedImage pageImage =
                renderer.renderImageWithDPI(pageNumber - 1, OCR_RENDER_DPI, ImageType.RGB);
        byte[] png = encodePng(pageImage);
        String artifactHash = hash(png);
        String artifactKey = "ocr-render/" + sourceHash + "/page-"
                + String.format(java.util.Locale.ROOT, "%04d", pageNumber) + "-300dpi.png";
        String idempotencyKey = sourceHash + ":page:" + pageNumber + ":300dpi:kor+eng";
        OcrClient.OcrResult result = ocrClient.recognize(new OcrClient.OcrRequest(
                png,
                PNG_MEDIA_TYPE,
                pageNumber,
                sourceHash,
                artifactKey,
                artifactHash,
                idempotencyKey));
        if (result == null
                || result.pageNumber() != pageNumber
                || !sourceHash.equals(result.sourceHash())
                || !artifactKey.equals(result.artifactKey())
                || !artifactHash.equals(result.artifactHash())
                || !idempotencyKey.equals(result.idempotencyKey())) {
            throw new TextExtractionException(
                    "PDF 페이지 " + pageNumber + "의 OCR provenance가 요청과 일치하지 않습니다.");
        }

        return PageExtractionResult.ocr(
                pageNumber,
                result.text(),
                PageExtractionResult.hashText(result.text()),
                candidateText,
                candidateHash,
                result.artifactKey(),
                result.artifactHash(),
                Map.of(
                        "renderDpi", OCR_RENDER_DPI,
                        "imageMediaType", PNG_MEDIA_TYPE,
                        "language", OcrClient.LANGUAGE),
                result.engine(),
                result.confidence(),
                List.of());
    }

    private static boolean isTextAdequate(String text) {
        long nonWhitespace = text.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .count();
        if (nonWhitespace < MINIMUM_TEXT_CODEPOINTS) {
            return false;
        }
        long readable = text.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .filter(PdfBoxTextExtractor::isReadable)
                .count();
        return (double) readable / nonWhitespace >= MINIMUM_READABLE_RATIO;
    }

    private static boolean isReadable(int codePoint) {
        return Character.isLetterOrDigit(codePoint)
                || Character.getType(codePoint) == Character.CONNECTOR_PUNCTUATION
                || Character.getType(codePoint) == Character.DASH_PUNCTUATION
                || Character.getType(codePoint) == Character.START_PUNCTUATION
                || Character.getType(codePoint) == Character.END_PUNCTUATION
                || Character.getType(codePoint) == Character.OTHER_PUNCTUATION
                || Character.getType(codePoint) == Character.MATH_SYMBOL
                || Character.getType(codePoint) == Character.CURRENCY_SYMBOL;
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("PNG encoder is unavailable");
        }
        return output.toByteArray();
    }

    private static String hash(byte[] value) {
        return PageExtractionResult.hashBytes(value);
    }
}
