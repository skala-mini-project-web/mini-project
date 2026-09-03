package com.crosschecklab.domain.document.extraction;

import com.crosschecklab.domain.document.DocumentMediaType;
import java.io.IOException;
import java.io.InputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// PDF 본문 추출. real-extraction 프로파일에서만 활성화된다.
@Component
@Profile("real-extraction")
public class PdfBoxTextExtractor implements BinaryTextExtractor {

    @Override
    public DocumentMediaType supportedMediaType() {
        return DocumentMediaType.PDF;
    }

    @Override
    public String extract(InputStream content) {
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(content))) {
            return new PDFTextStripper().getText(document);
        } catch (IOException e) {
            throw new TextExtractionException("PDF 텍스트 추출에 실패했습니다.", e);
        }
    }
}
