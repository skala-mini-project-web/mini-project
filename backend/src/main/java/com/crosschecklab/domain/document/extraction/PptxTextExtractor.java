package com.crosschecklab.domain.document.extraction;

import com.crosschecklab.domain.document.DocumentMediaType;
import java.io.IOException;
import java.io.InputStream;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// PPTX 본문 추출. 슬라이드 순서대로 텍스트 도형만 이어 붙인다.
// real-extraction 프로파일에서만 활성화된다.
@Component
@Profile("real-extraction")
public class PptxTextExtractor implements BinaryTextExtractor {

    @Override
    public DocumentMediaType supportedMediaType() {
        return DocumentMediaType.PPTX;
    }

    @Override
    public String extract(InputStream content) {
        try (XMLSlideShow slideShow = new XMLSlideShow(content)) {
            StringBuilder text = new StringBuilder();
            for (XSLFSlide slide : slideShow.getSlides()) {
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        text.append(textShape.getText()).append(System.lineSeparator());
                    }
                }
            }
            return text.toString();
        } catch (IOException e) {
            throw new TextExtractionException("PPTX 텍스트 추출에 실패했습니다.", e);
        }
    }
}
